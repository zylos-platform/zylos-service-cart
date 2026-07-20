package app.zylos.cart.adapter.out.relay;

import app.zylos.cart.adapter.out.persistence.dynamodb.CartOutboxRecordFactory;
import app.zylos.cart.adapter.out.persistence.dynamodb.OutboxRecordItem;
import app.zylos.cart.config.ZylosCartProperties;
import app.zylos.contracts.cart.v1.CartEvent;
import com.github.f4b6a3.uuid.UuidCreator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sharded polling-publisher relay. Each cycle, for every shard, it
 * claims a lease, drains the shard's pending outbox in order, and releases the lease.
 *
 * <p>Three mechanisms carry the guarantees, and it is worth being precise about which does what:
 *
 * <ul>
 *   <li><b>Producer epoch fencing</b> (shard-scoped {@code transactional.id}) is the actual mutual
 *       exclusion. A zombie drainer cannot write, regardless of how long its JVM stalled.
 *   <li><b>The lease</b> is an optimization: it stops instances doing work they would lose, and is
 *       deadline-checked and renewed mid-batch. It is not, and cannot be, a correctness mechanism.
 *   <li><b>Last-writer-wins on the monotonic aggregate version</b> makes the compacted snapshot topic
 *       self-correcting if anything does slip through.
 * </ul>
 *
 * <p>Publish-then-mark gives at-least-once delivery; consumers are idempotent by mandate and must read
 * with {@code read_committed}.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int SHARDS = CartOutboxRecordFactory.OUTBOX_SHARDS;

    private final OutboxStore store;
    private final CartEventPublisher publisher;
    private final ZylosCartProperties cartProperties;
    private final String instanceId;
    private final int shardOffset;
    private final Counter poisoned;
    private final Counter publishErrors;
    private final Counter fenced;
    private final Counter leaseLost;

    public OutboxRelay(
        OutboxStore store,
        CartEventPublisher publisher,
        ZylosCartProperties cartProperties,
        MeterRegistry registry) {
        this.store = store;
        this.publisher = publisher;
        this.cartProperties = cartProperties;
        this.instanceId = UuidCreator.getTimeOrderedEpoch().toString();
        this.shardOffset = Math.floorMod(instanceId.hashCode(), SHARDS);
        this.poisoned = Counter.builder("zylos.cart.relay.poisoned")
            .description("Undecodable outbox records moved to the DLQ")
            .register(registry);
        this.publishErrors = Counter.builder("zylos.cart.relay.errors")
            .description("Transient publish failures; the record is retried")
            .register(registry);
        this.fenced = Counter.builder("zylos.cart.relay.fenced")
            .description("Shards abandoned because this instance lost the producer epoch")
            .register(registry);
        this.leaseLost = Counter.builder("zylos.cart.relay.lease.lost")
            .description("Batches abandoned because the lease expired or was taken over")
            .register(registry);
    }

    @Scheduled(fixedDelayString = "${zylos.cart.relay.poll-interval-ms:1000}")
    void scheduledDrain() {
        try {
            drainOnce();
        } catch (RuntimeException e) {
            log.warn("Relay drain cycle failed", e);
        }
    }

    /**
     * One full pass over all shards this instance can claim.
     */
    public void drainOnce() {
        for (int i = 0; i < SHARDS; i++) {
            int shard = (shardOffset + i) % SHARDS;

            Optional<RelayLease> claimed =
                store.tryAcquireLease(shard, instanceId, Duration.ofSeconds(cartProperties.relay().leaseTtlSeconds()), Instant.now());

            if (claimed.isEmpty()) {
                continue;
            }

            RelayLease lease = claimed.get();
            try {
                drainShard(lease);
            } catch (RelayFencedException e) {
                log.warn("Fenced on shard {}; abandoning this cycle", shard, e);
                fenced.increment();
            } catch (RelayPublishException e) {
                publishErrors.increment();
                log.warn("Publish failed on shard {}; will retry next cycle", shard, e);
            } finally {
                store.releaseLease(lease);
            }
        }
    }

    public int drainShard(RelayLease lease) {
        int shard = lease.shard();
        List<OutboxRecordItem> batch = store.readPending(shard, cartProperties.relay().batchSize());

        if (batch.isEmpty()) {
            return 0;
        }

        // Decode first, outside the transaction: a poison record can never succeed, so it is moved to
        // the DLQ immediately rather than blocking the shard head-of-line.
        List<OutboxRecordItem> records = new ArrayList<>(batch.size());
        List<CartEventPublisher.PendingEvent> events = new ArrayList<>(batch.size());
        Instant decodedAt = Instant.now();

        for (OutboxRecordItem item : batch) {
            if (item.status() == null || !item.status().equals("PENDING")) {
                continue; // Phantom read from the GSI
            }

            try {
                CartEvent envelope = CartEvent.fromByteBuffer(item.payload().asByteBuffer());
                records.add(item);
                events.add(new CartEventPublisher.PendingEvent(item.cartId(), envelope, item.terminal(), item.occurredAt()));
            } catch (Exception decodeFailure) {
                log.error("Poison outbox record {} on shard {} -> DLQ", item.outboxId(), shard, decodeFailure);
                store.moveToDlq(item, Duration.ofHours(cartProperties.relay().publishedRetentionHours()), decodedAt);
                poisoned.increment();
            }
        }

        if (events.isEmpty()) {
            return 0;
        }

        AtomicReference<RelayLease> current = new AtomicReference<>(lease);
        int committed = publisher.publishBatch(shard, events, sentSoFar -> guard(current, sentSoFar));

        Instant markedAt = Instant.now();
        List<CompletableFuture<UpdateItemResponse>> updateFutures = new ArrayList<>(committed);

        for (int i = 0; i < committed; i++) {
            updateFutures.add(
                store.markPublishedAsync(records.get(i), Duration.ofHours(cartProperties.relay().publishedRetentionHours()), markedAt)
            );
        }

        try {
            CompletableFuture.allOf(updateFutures.toArray(CompletableFuture<?>[]::new)).join();
        } catch (CompletionException e) {
            // A partial failure happened.
            log.warn("Failed to mark some records as published. They will remain in the GSI and be redelivered next cycle.", e);
        }

        return committed;
    }

    /**
     * Runs between sends: renews the lease periodically and stops the batch if the deadline is within
     * the safety margin, or if the lease was taken over.
     */
    private boolean guard(AtomicReference<RelayLease> current, int sentSoFar) {
        RelayLease lease = current.get();
        Instant now = Instant.now();

        if (sentSoFar > 0 && sentSoFar % cartProperties.relay().leaseRenewEveryRecords() == 0) {
            Optional<RelayLease> renewed = store.renewLease(lease, Duration.ofSeconds(cartProperties.relay().leaseTtlSeconds()), now);

            if (renewed.isEmpty()) {
                leaseLost.increment();
                return false;
            }
            current.set(renewed.get());
            lease = renewed.get();
        }

        if (lease.isExpiringWithin(Duration.ofMillis(cartProperties.relay().leaseSafetyMarginMs()), now)) {
            leaseLost.increment();
            return false;
        }
        return true;
    }
}
