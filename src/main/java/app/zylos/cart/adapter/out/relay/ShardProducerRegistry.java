package app.zylos.cart.adapter.out.relay;

import app.zylos.cart.config.ZylosCartProperties;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns one transactional Kafka producer per outbox shard, keyed by a shard-scoped
 * {@code transactional.id} ({@code cart-relay-shard-<N>}) rather than a pod-scoped one.
 *
 * <p>This is the relay's real mutual-exclusion mechanism. When a second instance claims a shard and
 * calls {@code initTransactions()}, the broker bumps the producer epoch for that transactional id and
 * every subsequent send from the previous holder fails with {@code ProducerFencedException} — a true
 * fencing token, enforced broker-side. A zombie drainer whose JVM stalled past its lease therefore
 * cannot write, which is a guarantee no lease deadline can provide on its own.
 */
@Component
public class ShardProducerRegistry {

    private static final Logger log = LoggerFactory.getLogger(ShardProducerRegistry.class);

    private final Map<Integer, Producer<String, byte[]>> producers = new ConcurrentHashMap<>();
    private final String bootstrapServers;
    private final int transactionTimeoutMs;

    public ShardProducerRegistry(KafkaProperties kafkaProperties, ZylosCartProperties cartProperties) {
        this.bootstrapServers = kafkaProperties.getBootstrapServers().getFirst();

        // The broker aborts a transaction that outlives this; it must comfortably exceed the worst-case
        // batch duration or the coordinator will abort a batch we are still legitimately sending.
        ZylosCartProperties.Relay relay = cartProperties.relay();
        this.transactionTimeoutMs = (int) Math.min(840_000L, relay.sendTimeoutSeconds() * 1000L * relay.batchSize() + 60_000L);
    }

    /**
     * Returns the shard's producer, initializing transactions on first use.
     */
    public Producer<String, byte[]> producerFor(int shard) {
        return producers.computeIfAbsent(shard, this::createAndInit);
    }

    /**
     * Discards a fenced producer so the next claim re-initializes and re-acquires an epoch.
     */
    public void evict(int shard) {
        Producer<String, byte[]> producer = producers.remove(shard);

        if (producer != null) {
            try {
                producer.close(Duration.ofSeconds(5));
            } catch (RuntimeException e) {
                log.warn("Failed closing fenced producer for shard {}", shard, e);
            }
        }
    }

    private Producer<String, byte[]> createAndInit(int shard) {
        Map<String, Object> config = Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
            ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5,
            ProducerConfig.TRANSACTIONAL_ID_CONFIG, "cart-relay-shard-" + shard,
            ProducerConfig.TRANSACTION_TIMEOUT_CONFIG, transactionTimeoutMs);

        Producer<String, byte[]> producer = new KafkaProducer<>(config);
        producer.initTransactions();
        return producer;
    }

    @PreDestroy
    void closeAll() {
        producers.values().forEach(producer -> producer.close(Duration.ofSeconds(5)));
        producers.clear();
    }
}
