package app.zylos.cart.adapter.out.relay;

import static app.zylos.cart.adapter.out.relay.CartTestFixtures.defaultCartProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.zylos.cart.adapter.out.relay.CartEventPublisher.PendingEvent;
import app.zylos.contracts.cart.v1.CartEvent;
import app.zylos.contracts.cart.v1.CartOwner;
import app.zylos.contracts.cart.v1.CartState;

import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CartEventPublisherTest {

    private static final int SHARD = 3;
    private static final String EVENTS_TOPIC = "cart.cart.events.v1";
    private static final String SNAPSHOT_TOPIC = "cart.cart.snapshot.v1";

    private ShardProducerRegistry registry;
    private Producer<String, byte[]> producer;
    private CartEventPublisher publisher;

    private static PendingEvent event(String cartId, String eventType, long version, boolean terminal) {
        CartEvent envelope = CartEvent.newBuilder()
                .setEventId("event-" + version)
                .setEventType(eventType)
                .setEventVersion(1)
                .setAggregateId(cartId)
                .setAggregateType("cart")
                .setOccurredAt(Instant.now())
                .setCorrelationId("corr-1")
                .setCausationId("cause-1")
                .setProducer(app.zylos.contracts.common.v1.Producer.newBuilder()
                        .setService("zylos-service-cart")
                        .setVersion("test")
                        .build())
                .setPayload(CartState.newBuilder()
                        .setCartId(cartId)
                        .setOwner(CartOwner.newBuilder()
                                .setOwnerType("CUSTOMER")
                                .setOwnerId("customer-1")
                                .build())
                        .setStatus(terminal ? "CONVERTED" : "ACTIVE")
                        .setCurrency("USD")
                        .setLines(List.of())
                        .setVersion(version)
                        .setCreatedAt(Instant.now())
                        .setUpdatedAt(Instant.now())
                        .setConvertedOrderId(terminal ? "order-1" : null)
                        .setMergedIntoCartId(null)
                        .build())
                .build();
        return new PendingEvent(cartId, envelope, terminal, Instant.now());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(ShardProducerRegistry.class);
        producer = mock(Producer.class);
        when(registry.producerFor(SHARD)).thenReturn(producer);

        KafkaAvroSerializer serializer = new KafkaAvroSerializer(
                new MockSchemaRegistryClient(),
                Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://publisher-test"));

        publisher = new CartEventPublisher(registry, serializer, defaultCartProperties(), new SimpleMeterRegistry());
    }

    @SuppressWarnings("unchecked")
    private List<ProducerRecord<String, byte[]>> capturedSends(int expected) {
        ArgumentCaptor<ProducerRecord<String, byte[]>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer, times(expected)).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void wrapsTheWholeBatchInASingleTransaction() {
        int sent = publisher.publishBatch(
                SHARD,
                List.of(event("cart-1", "CartLineAdded", 1L, false), event("cart-1", "CartLineAdded", 2L, false)),
                ignored -> true);

        assertThat(sent).isEqualTo(2);
        verify(producer, times(1)).beginTransaction();
        verify(producer, times(1)).commitTransaction();
        verify(producer, never()).abortTransaction();
    }

    @Test
    void sendsEachEventToBothTopicsKeyedByCartId() {
        publisher.publishBatch(SHARD, List.of(event("cart-1", "CartLineAdded", 1L, false)), ignored -> true);

        List<ProducerRecord<String, byte[]>> sends = capturedSends(2);
        assertThat(sends).extracting(ProducerRecord::topic).containsExactly(EVENTS_TOPIC, SNAPSHOT_TOPIC);
        assertThat(sends).extracting(ProducerRecord::key).containsOnly("cart-1");
        assertThat(sends).allSatisfy(record -> assertThat(record.value()).isNotEmpty());
    }

    @Test
    void publishesATombstoneToTheSnapshotTopicForTerminalEvents() {
        publisher.publishBatch(SHARD, List.of(event("cart-1", "CartConverted", 2L, true)), ignored -> true);

        List<ProducerRecord<String, byte[]>> sends = capturedSends(2);
        ProducerRecord<String, byte[]> events = sends.get(0);
        ProducerRecord<String, byte[]> snapshot = sends.get(1);

        assertThat(events.topic()).isEqualTo(EVENTS_TOPIC);
        assertThat(events.value()).isNotEmpty(); // the event log keeps the full record
        assertThat(snapshot.topic()).isEqualTo(SNAPSHOT_TOPIC);
        assertThat(snapshot.value()).isNull(); // compaction tombstone
        assertThat(snapshot.key()).isEqualTo("cart-1");
    }

    @Test
    void abortsAndSendsNothingWhenTheGuardVetoesImmediately() {
        int sent =
                publisher.publishBatch(SHARD, List.of(event("cart-1", "CartLineAdded", 1L, false)), ignored -> false);

        assertThat(sent).isZero();
        verify(producer).beginTransaction();
        verify(producer, never()).send(any());
        verify(producer).abortTransaction();
        verify(producer, never()).commitTransaction();
    }

    @Test
    void commitsOnlyThePrefixSentBeforeTheGuardVetoed() {
        List<PendingEvent> batch = List.of(
                event("cart-1", "CartLineAdded", 1L, false),
                event("cart-1", "CartLineAdded", 2L, false),
                event("cart-1", "CartLineAdded", 3L, false));

        int sent = publisher.publishBatch(SHARD, batch, sentSoFar -> sentSoFar < 2);

        assertThat(sent).isEqualTo(2);
        capturedSends(4); // two events x two topics
        verify(producer).commitTransaction();
        verify(producer, never()).abortTransaction();
    }

    @Test
    void returnsZeroForAnEmptyBatchWithoutTouchingTheProducer() {
        assertThat(publisher.publishBatch(SHARD, List.of(), ignored -> true)).isZero();

        verify(registry, never()).producerFor(SHARD);
        verify(producer, never()).beginTransaction();
    }

    @Test
    void evictsTheProducerAndSignalsFencingWhenTheEpochIsLost() {
        when(producer.send(any())).thenThrow(new ProducerFencedException("epoch bumped"));

        assertThatThrownBy(() -> publisher.publishBatch(
                        SHARD, List.of(event("cart-1", "CartLineAdded", 1L, false)), ignored -> true))
                .isInstanceOf(RelayFencedException.class);

        verify(registry).evict(SHARD);
        verify(producer, never()).commitTransaction();
    }

    @Test
    void abortsAndSignalsATransientFailure() {
        when(producer.send(any())).thenThrow(new KafkaException("broker unavailable"));

        assertThatThrownBy(() -> publisher.publishBatch(
                        SHARD, List.of(event("cart-1", "CartLineAdded", 1L, false)), ignored -> true))
                .isInstanceOf(RelayPublishException.class);

        verify(producer).abortTransaction();
        verify(producer, never()).commitTransaction();
        verify(registry, never()).evict(SHARD);
    }

    @Test
    void evictsTheProducerWhenEvenTheAbortFails() {
        when(producer.send(any())).thenThrow(new KafkaException("broker unavailable"));
        doThrow(new KafkaException("producer unusable")).when(producer).abortTransaction();

        assertThatThrownBy(() -> publisher.publishBatch(
                        SHARD, List.of(event("cart-1", "CartLineAdded", 1L, false)), ignored -> true))
                .isInstanceOf(RelayPublishException.class);

        verify(registry).evict(SHARD);
    }

    @Test
    void failsWhenCommitItselfIsFenced() {
        doThrow(new ProducerFencedException("epoch bumped")).when(producer).commitTransaction();

        assertThatThrownBy(() -> publisher.publishBatch(
                        SHARD, List.of(event("cart-1", "CartLineAdded", 1L, false)), ignored -> true))
                .isInstanceOf(RelayFencedException.class);

        verify(registry).evict(SHARD);
    }
}
