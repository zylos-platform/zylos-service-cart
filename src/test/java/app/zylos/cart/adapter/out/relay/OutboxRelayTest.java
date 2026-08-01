package app.zylos.cart.adapter.out.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import app.zylos.cart.adapter.out.persistence.dynamodb.OutboxRecordItem;
import app.zylos.cart.adapter.out.relay.CartEventPublisher.BatchGuard;
import app.zylos.cart.adapter.out.relay.CartEventPublisher.PendingEvent;
import app.zylos.cart.config.ZylosCartOutboxProperties;
import app.zylos.cart.config.ZylosCartProperties;
import app.zylos.contracts.cart.v1.CartEvent;
import app.zylos.contracts.cart.v1.CartOwner;
import app.zylos.contracts.cart.v1.CartState;
import app.zylos.contracts.common.v1.Producer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;

class OutboxRelayTest {

    private static final int SHARD = 3;
    private static final int BATCH_SIZE = 100;
    private static final long LEASE_TTL_SECONDS = 30L;
    private static final long SAFETY_MARGIN_MS = 5_000L;
    private static final int RENEW_EVERY = 2;

    private OutboxStore store;
    private CartEventPublisher publisher;
    private OutboxRelay relay;

    private static RelayLease healthyLease() {
        return new RelayLease(SHARD, "instance-1", Instant.now().plusSeconds(LEASE_TTL_SECONDS));
    }

    private static RelayLease expiringLease() {
        // Inside the safety margin: the guard must veto before the first send.
        return new RelayLease(SHARD, "instance-1", Instant.now().plusMillis(SAFETY_MARGIN_MS / 2));
    }

    private static CartEvent envelope(String cartId, String eventType, long version) {
        return CartEvent.newBuilder()
                .setEventId("event-" + version)
                .setEventType(eventType)
                .setEventVersion(1)
                .setAggregateId(cartId)
                .setAggregateType("cart")
                .setOccurredAt(Instant.now())
                .setCorrelationId("corr-1")
                .setCausationId("cause-1")
                .setProducer(Producer.newBuilder()
                        .setService("zylos-service-cart")
                        .setVersion("test")
                        .build())
                .setPayload(CartState.newBuilder()
                        .setCartId(cartId)
                        .setOwner(CartOwner.newBuilder()
                                .setOwnerType("CUSTOMER")
                                .setOwnerId("customer-1")
                                .build())
                        .setStatus("ACTIVE")
                        .setCurrency("USD")
                        .setLines(List.of())
                        .setVersion(version)
                        .setCreatedAt(Instant.now())
                        .setUpdatedAt(Instant.now())
                        .setConvertedOrderId(null)
                        .setMergedIntoCartId(null)
                        .build())
                .build();
    }

    private static OutboxRecordItem pendingRecord(String cartId, String eventType, long version, boolean terminal)
            throws IOException {
        return baseRecord(cartId, eventType, version, terminal)
                .payload(SdkBytes.fromByteBuffer(
                        envelope(cartId, eventType, version).toByteBuffer()))
                .build();
    }

    private static OutboxRecordItem poisonRecord(String cartId) {
        return baseRecord(cartId, "CartLineAdded", 1L, false)
                .payload(SdkBytes.fromUtf8String("this is not avro"))
                .build();
    }

    private static OutboxRecordItem.Builder baseRecord(
            String cartId, String eventType, long version, boolean terminal) {
        String outboxId = "outbox-" + cartId + "-" + version;
        return OutboxRecordItem.builder()
                .pk("OUTBOX#" + SHARD)
                .sk(outboxId)
                .entityType("Outbox")
                .shardId(SHARD)
                .outboxId(outboxId)
                .eventId("event-" + version)
                .eventType(eventType)
                .aggregateId(cartId)
                .aggregateType("cart")
                .cartId(cartId)
                .occurredAt(Instant.now())
                .terminal(terminal)
                .gsi2pk("PENDING#" + SHARD)
                .gsi2sk(outboxId)
                .status("PENDING")
                .expiresAt(null); // pending records must never carry a TTL
    }

    private static Throwable catchRuntime(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    @BeforeEach
    void setUp() {
        store = mock(OutboxStore.class);
        publisher = mock(CartEventPublisher.class);
        ZylosCartProperties props = CartTestFixtures.builder()
                .leaseTtlSeconds(LEASE_TTL_SECONDS)
                .leaseSafetyMarginMs(SAFETY_MARGIN_MS)
                .leaseRenewEveryRecords(RENEW_EVERY)
                .batchSize(BATCH_SIZE)
                .build();

        relay = new OutboxRelay(
                store, publisher, props, new ZylosCartOutboxProperties(16, 16), new SimpleMeterRegistry());
    }

    /**
     * Mirrors the real publisher: walk the batch, consult the guard, stop on veto.
     */
    private void publisherHonoursGuard() {
        when(publisher.publishBatch(anyInt(), any(), any())).thenAnswer(invocation -> {
            List<PendingEvent> batch = invocation.getArgument(1);
            BatchGuard guard = invocation.getArgument(2);
            int sent = 0;

            for (int i = 0; i < batch.size(); i++) {
                if (!guard.shouldContinue(sent)) {
                    break;
                }
                sent++;
            }
            return sent;
        });
    }

    private void leaseRenewalSucceeds() {
        when(store.renewLease(any(), any(), any())).thenAnswer(invocation -> {
            RelayLease lease = invocation.getArgument(0);
            return Optional.of(
                    new RelayLease(lease.shard(), lease.owner(), Instant.now().plusSeconds(LEASE_TTL_SECONDS)));
        });
    }

    @Test
    void publishesTheBatchThenMarksEachRecordPublished() throws Exception {
        OutboxRecordItem first = pendingRecord("cart-1", "CartLineAdded", 1L, false);
        OutboxRecordItem second = pendingRecord("cart-1", "CartLineQuantityChanged", 2L, false);

        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(List.of(first, second));
        when(store.markPublishedAsync(any(), any(Duration.class), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().build()));

        leaseRenewalSucceeds();
        publisherHonoursGuard();

        int drained = relay.drainShard(healthyLease());

        assertThat(drained).isEqualTo(2);
        InOrder order = inOrder(publisher, store);
        order.verify(publisher).publishBatch(eq(SHARD), any(), any());
        order.verify(store).markPublishedAsync(eq(first), any(Duration.class), any(Instant.class));
        order.verify(store).markPublishedAsync(eq(second), any(Duration.class), any(Instant.class));
        verify(store, never()).moveToDlq(any(), any(), any());
    }

    @Test
    void passesEventsToThePublisherInIndexOrderPreservingTerminalFlag() throws Exception {
        OutboxRecordItem added = pendingRecord("cart-1", "CartLineAdded", 1L, false);
        OutboxRecordItem converted = pendingRecord("cart-1", "CartConverted", 2L, true);

        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(List.of(added, converted));
        when(store.markPublishedAsync(any(), any(Duration.class), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().build()));

        leaseRenewalSucceeds();

        when(publisher.publishBatch(anyInt(), any(), any())).thenAnswer(invocation -> {
            List<PendingEvent> batch = invocation.getArgument(1);
            assertThat(batch).hasSize(2);
            assertThat(batch.get(0).envelope().getEventType()).isEqualTo("CartLineAdded");
            assertThat(batch.get(0).terminal()).isFalse();
            assertThat(batch.get(1).envelope().getEventType()).isEqualTo("CartConverted");
            assertThat(batch.get(1).terminal()).isTrue();
            assertThat(batch).allSatisfy(event -> assertThat(event.cartId()).isEqualTo("cart-1"));
            return batch.size();
        });

        assertThat(relay.drainShard(healthyLease())).isEqualTo(2);
    }

    @Test
    void doesNothingWhenTheShardHasNoPendingRecords() {
        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(List.of());

        assertThat(relay.drainShard(healthyLease())).isZero();

        verify(publisher, never()).publishBatch(anyInt(), any(), any());
        verify(store, never()).markPublishedAsync(any(), any(), any());
    }

    @Test
    void marksOnlyTheCommittedPrefixPublished() throws Exception {
        OutboxRecordItem first = pendingRecord("cart-1", "CartLineAdded", 1L, false);
        OutboxRecordItem second = pendingRecord("cart-1", "CartLineQuantityChanged", 2L, false);
        OutboxRecordItem third = pendingRecord("cart-1", "CartLineRemoved", 3L, false);

        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(List.of(first, second, third));
        when(publisher.publishBatch(anyInt(), any(), any())).thenReturn(1); // guard stopped after one
        when(store.markPublishedAsync(any(), any(Duration.class), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().build()));

        int drained = relay.drainShard(healthyLease());

        assertThat(drained).isEqualTo(1);
        verify(store).markPublishedAsync(eq(first), any(), any());
        verify(store, never()).markPublishedAsync(eq(second), any(), any());
        verify(store, never()).markPublishedAsync(eq(third), any(), any());
    }

    @Test
    void retainsEveryRecordWhenThePublishFails() throws Exception {
        OutboxRecordItem first = pendingRecord("cart-1", "CartLineAdded", 1L, false);
        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(List.of(first));
        when(publisher.publishBatch(anyInt(), any(), any()))
                .thenThrow(new RelayPublishException("kafka unavailable", new RuntimeException()));

        RelayLease lease = healthyLease();

        assertThat(catchRuntime(() -> relay.drainShard(lease))).isInstanceOf(RelayPublishException.class);
        // Nothing marked published -> the record stays in the sparse index and is retried next cycle.
        verify(store, never()).markPublishedAsync(any(), any(), any());
    }

    @Test
    void republishesARecordThatWasCommittedButNotMarked() throws Exception {
        OutboxRecordItem first = pendingRecord("cart-1", "CartLineAdded", 1L, false);
        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(List.of(first));
        leaseRenewalSucceeds();
        publisherHonoursGuard();

        // markPublishedAsync fails the first time: the record remains pending and is drained again.
        doThrow(new RuntimeException("dynamo blip")).when(store).markPublishedAsync(eq(first), any(), any());

        assertThat(catchRuntime(() -> relay.drainShard(healthyLease()))).isInstanceOf(RuntimeException.class);

        when(store.markPublishedAsync(any(), any(Duration.class), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().build()));
        relay.drainShard(healthyLease());

        // Published twice — at-least-once, absorbed by idempotent consumers.
        verify(publisher, times(2)).publishBatch(eq(SHARD), any(), any());
    }

    @Test
    void abortsTheBatchWhenTheLeaseIsAlreadyExpiring() throws Exception {
        when(store.readPending(SHARD, BATCH_SIZE))
                .thenReturn(List.of(pendingRecord("cart-1", "CartLineAdded", 1L, false)));
        when(store.markPublishedAsync(any(), any(Duration.class), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().build()));

        publisherHonoursGuard();

        int drained = relay.drainShard(expiringLease());

        assertThat(drained).isZero();
        verify(store, never()).markPublishedAsync(any(), any(), any());
    }

    @Test
    void renewsTheLeaseMidBatchAndKeepsGoing() throws Exception {
        // RENEW_EVERY = 2, so the guard renews at sentSoFar == 2 and == 4.
        List<OutboxRecordItem> batch = List.of(
                pendingRecord("cart-1", "CartLineAdded", 1L, false),
                pendingRecord("cart-1", "CartLineAdded", 2L, false),
                pendingRecord("cart-1", "CartLineAdded", 3L, false),
                pendingRecord("cart-1", "CartLineAdded", 4L, false),
                pendingRecord("cart-1", "CartLineAdded", 5L, false));
        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(batch);
        when(store.markPublishedAsync(any(), any(Duration.class), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().build()));

        leaseRenewalSucceeds();
        publisherHonoursGuard();

        int drained = relay.drainShard(healthyLease());

        assertThat(drained).isEqualTo(5);
        verify(store, times(2)).renewLease(any(), any(), any());
    }

    @Test
    void stopsMidBatchWhenTheLeaseWasTakenOver() throws Exception {
        List<OutboxRecordItem> batch = List.of(
                pendingRecord("cart-1", "CartLineAdded", 1L, false),
                pendingRecord("cart-1", "CartLineAdded", 2L, false),
                pendingRecord("cart-1", "CartLineAdded", 3L, false),
                pendingRecord("cart-1", "CartLineAdded", 4L, false));
        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(batch);
        when(store.renewLease(any(), any(), any())).thenReturn(Optional.empty()); // stolen
        when(store.markPublishedAsync(any(), any(Duration.class), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().build()));

        publisherHonoursGuard();

        int drained = relay.drainShard(healthyLease());

        // Two events got through before the renewal at sentSoFar == 2 failed.
        assertThat(drained).isEqualTo(2);
        verify(store).markPublishedAsync(eq(batch.get(0)), any(), any());
        verify(store).markPublishedAsync(eq(batch.get(1)), any(), any());
        verify(store, never()).markPublishedAsync(eq(batch.get(2)), any(), any());
    }

    @Test
    void movesPoisonToTheDlqAndPublishesTheRest() throws Exception {
        OutboxRecordItem poison = poisonRecord("cart-1");
        OutboxRecordItem good = pendingRecord("cart-2", "CartLineAdded", 1L, false);
        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(List.of(poison, good));
        when(store.markPublishedAsync(any(), any(Duration.class), any(Instant.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        UpdateItemResponse.builder().build()));

        leaseRenewalSucceeds();
        publisherHonoursGuard();

        int drained = relay.drainShard(healthyLease());

        assertThat(drained).isEqualTo(1);
        verify(store).moveToDlq(eq(poison), any(Duration.class), any(Instant.class));
        verify(store).markPublishedAsync(eq(good), any(), any());
    }

    @Test
    void doesNotOpenATransactionWhenEveryRecordIsPoison() {
        when(store.readPending(SHARD, BATCH_SIZE)).thenReturn(List.of(poisonRecord("cart-1")));

        assertThat(relay.drainShard(healthyLease())).isZero();

        verify(store).moveToDlq(any(), any(), any());
        verify(publisher, never()).publishBatch(anyInt(), any(), any());
    }

    @Test
    void skipsShardsWhoseLeaseCannotBeAcquired() {
        when(store.tryAcquireLease(anyInt(), any(), any(), any())).thenReturn(Optional.empty());

        relay.drainOnce();

        verify(store, never()).readPending(anyInt(), anyInt());
        verify(store, never()).releaseLease(any());
    }

    @Test
    void releasesTheLeaseEvenWhenTheShardFails() throws Exception {
        when(store.tryAcquireLease(anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.of(new RelayLease(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        Instant.now().plusSeconds(LEASE_TTL_SECONDS))));
        when(store.readPending(anyInt(), anyInt()))
                .thenReturn(List.of(pendingRecord("cart-1", "CartLineAdded", 1L, false)));
        when(publisher.publishBatch(anyInt(), any(), any()))
                .thenThrow(new RelayPublishException("kafka down", new RuntimeException()));

        relay.drainOnce(); // must not propagate

        verify(store, times(16)).releaseLease(any(RelayLease.class));
    }

    @Test
    void abandonsAShardWhenFencedButContinuesTheSweep() throws Exception {
        when(store.tryAcquireLease(anyInt(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.of(new RelayLease(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        Instant.now().plusSeconds(LEASE_TTL_SECONDS))));
        when(store.readPending(anyInt(), anyInt()))
                .thenReturn(List.of(pendingRecord("cart-1", "CartLineAdded", 1L, false)));
        when(publisher.publishBatch(anyInt(), any(), any()))
                .thenThrow(new RelayFencedException("epoch lost", new RuntimeException()));

        relay.drainOnce(); // must not propagate

        verify(store, times(16)).releaseLease(any(RelayLease.class));
        verify(store, never()).markPublished(any(), any(), any());
    }
}
