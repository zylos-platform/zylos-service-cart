package app.zylos.cart.adapter.out.relay;

import app.zylos.cart.config.ZylosCartProperties;
import app.zylos.contracts.cart.v1.CartEvent;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Publishes one decoded cart event to both topics from the same outbox record: full ECST state to the
 * events topic (always) and to the snapshot topic (or a null-value tombstone on a terminal event).
 */
@Component
public class CartEventPublisher {

    private final ShardProducerRegistry producers;
    private final KafkaAvroSerializer serializer;
    private final ZylosCartProperties cartProperties;
    private final Counter published;
    private final Counter aborted;
    private final Timer eventAge;

    public CartEventPublisher(
        ShardProducerRegistry producers,
        KafkaAvroSerializer cartEventAvroSerializer,
        ZylosCartProperties cartProperties,
        MeterRegistry registry) {
        this.producers = producers;
        this.serializer = cartEventAvroSerializer;
        this.cartProperties = cartProperties;
        this.published = Counter.builder("zylos.cart.relay.published")
            .description("Cart events published to Kafka by the outbox relay")
            .register(registry);
        this.aborted = Counter.builder("zylos.cart.relay.transactions.aborted")
            .description("Relay transactions aborted (lease expiry or send failure)")
            .register(registry);
        this.eventAge = Timer.builder("zylos.cart.relay.event.age")
            .description("Age of a cart event at publish time (write-to-publish lag)")
            .register(registry);
    }

    public int publishBatch(int shard, List<PendingEvent> batch, BatchGuard guard) {
        if (batch.isEmpty()) {
            return 0;
        }

        Producer<String, byte[]> producer = producers.producerFor(shard);
        producer.beginTransaction();

        String eventsTopic = cartProperties.eventsTopic();
        String snapshotTopic = cartProperties.snapshotTopic();

        int sent = 0;
        try {
            for (PendingEvent event : batch) {
                if (!guard.shouldContinue(sent)) {
                    break;
                }

                byte[] eventBytes = serializer.serialize(eventsTopic, event.envelope());
                byte[] snapshotBytes = event.terminal() ? null : serializer.serialize(snapshotTopic, event.envelope());

                producer.send(new ProducerRecord<>(eventsTopic, event.cartId(), eventBytes));
                producer.send(new ProducerRecord<>(snapshotTopic, event.cartId(), snapshotBytes));
                sent++;
            }

            if (sent == 0) {
                producer.abortTransaction();
                aborted.increment();
                return 0;
            }

            producer.commitTransaction();
        } catch (ProducerFencedException fenced) {
            // Epoch lost: the transaction is already void and this producer is unusable.
            producers.evict(shard);
            aborted.increment();
            throw new RelayFencedException("Fenced while publishing shard " + shard, fenced);
        } catch (RuntimeException failure) {
            safeAbort(producer, shard);
            aborted.increment();
            throw new RelayPublishException("Failed to publish batch for shard " + shard, failure);
        }

        Instant now = Instant.now();
        for (int i = 0; i < sent; i++) {
            eventAge.record(Duration.between(batch.get(i).occurredAt(), now));
        }

        published.increment(sent);
        return sent;
    }

    private void safeAbort(Producer<String, byte[]> producer, int shard) {
        try {
            producer.abortTransaction();
        } catch (RuntimeException _) {
            // The producer is unusable; drop it so the next claim re-initializes.
            producers.evict(shard);
        }
    }

    /**
     * Called between sends so the caller can abandon a batch whose lease is expiring.
     */
    @FunctionalInterface
    public interface BatchGuard {
        /**
         * @return true to continue sending, false to abort the transaction.
         */
        boolean shouldContinue(int sentSoFar);
    }

    /**
     * One event decoded from its outbox record, ready to publish.
     */
    public record PendingEvent(String cartId, CartEvent envelope, boolean terminal, Instant occurredAt) {
    }
}
