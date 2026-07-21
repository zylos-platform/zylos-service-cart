package app.zylos.cart.adapter.out.relay;

import static app.zylos.cart.adapter.out.relay.CartTestFixtures.defaultCartProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidProducerEpochException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import app.zylos.cart.adapter.out.persistence.dynamodb.CartOutboxRecordFactory;
import app.zylos.cart.adapter.out.persistence.dynamodb.CartTableSchemas;
import app.zylos.cart.adapter.out.persistence.dynamodb.DynamoCartRepository;
import app.zylos.cart.adapter.out.persistence.dynamodb.OutboxRecordItem;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner.CustomerOwner;
import app.zylos.cart.domain.vo.*;
import app.zylos.contracts.cart.v1.CartEvent;

import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Testcontainers
class OutboxRelayIT {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"))
            // A single broker cannot satisfy the default replication factor of 3 for the transaction
            // state log; without these, initTransactions() hangs until timeout with no useful error.
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");

    @Container
    static final GenericContainer<?> dynamo = new GenericContainer<>(
                    DockerImageName.parse("amazon/dynamodb-local:3.2.0"))
            .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb")
            .withExposedPorts(8000);

    private static final Currency USD = Currency.getInstance("USD");
    private static final String TABLE = "zylos-cart";
    private static final String EVENTS_TOPIC = "cart.cart.events.v1";
    private static final String SNAPSHOT_TOPIC = "cart.cart.snapshot.v1";
    private static final String FENCING_TOPIC = "cart.relay.fencing-probe";
    private static final String SR_URL = "mock://relay-it";
    private static DynamoDbClient dynamoClient;
    private static DynamoDbAsyncClient asyncClient;
    private static DynamoDbEnhancedClient enhanced;
    private static MockSchemaRegistryClient schemaRegistry;

    private CartOutboxRecordFactory outboxFactory;
    private DynamoCartRepository repository;
    private OutboxStore store;
    private ShardProducerRegistry producers;
    private OutboxRelay relay;

    @BeforeAll
    static void startInfrastructure() {
        String endpoint = "http://%s:%d".formatted(dynamo.getHost(), dynamo.getMappedPort(8000));
        dynamoClient = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        asyncClient = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoClient).build();
        schemaRegistry = new MockSchemaRegistryClient();

        createTopics();
    }

    @AfterAll
    static void stopInfrastructure() {
        dynamoClient.close();
        asyncClient.close();
    }

    private static Cart newCartWithOneLine() {
        Cart cart = Cart.start(CartId.newId(), new CustomerOwner("customer-1"));
        cart.addLine(
                Sku.of("SKU-1"),
                Quantity.of(1),
                new PriceSnapshot(Money.ofMinor(1999, USD), 1L),
                "seller-1",
                LineOrigin.VALIDATED);
        return cart;
    }

    private static int shardOf(Cart cart) {
        return Math.floorMod(cart.id().value().toString().hashCode(), CartOutboxRecordFactory.OUTBOX_SHARDS);
    }

    private static CartEvent decode(String topic, byte[] value) {
        KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(
                schemaRegistry,
                Map.of(
                        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG,
                        SR_URL,
                        io.confluent.kafka.serializers.KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG,
                        true));
        return (CartEvent) deserializer.deserialize(topic, value);
    }

    /**
     * Consumes with {@code read_committed}, which is mandatory for every cart-topic consumer.
     */
    private static List<ConsumerRecord<String, byte[]>> consume(String topic, int expected, String cartId) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "it-%s-%s".formatted(topic, UUID.randomUUID()));
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        List<ConsumerRecord<String, byte[]>> collected = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(30);
            while (collected.size() < expected && Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, byte[]> polled = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : polled) {
                    if (record.key().equals(cartId)) {
                        collected.add(record);
                    }
                }
            }
        }
        assertThat(collected).hasSize(expected);
        return collected;
    }

    private static void createTopics() {
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (Admin admin = Admin.create(config)) {
            admin.createTopics(List.of(
                            new NewTopic(EVENTS_TOPIC, 6, (short) 1),
                            new NewTopic(SNAPSHOT_TOPIC, 6, (short) 1).configs(Map.of("cleanup.policy", "compact")),
                            new NewTopic(FENCING_TOPIC, 1, (short) 1)))
                    .all()
                    .get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted creating topics", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create topics", e);
        }
    }

    private static void createTable() {
        dynamoClient.createTable(builder -> builder.tableName(TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder()
                                .attributeName("PK")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("SK")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("GSI3PK")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("GSI3SK")
                                .attributeType(ScalarAttributeType.S)
                                .build())
                .keySchema(
                        KeySchemaElement.builder()
                                .attributeName("PK")
                                .keyType(KeyType.HASH)
                                .build(),
                        KeySchemaElement.builder()
                                .attributeName("SK")
                                .keyType(KeyType.RANGE)
                                .build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName(CartTableSchemas.GSI3_OUTBOX_PENDING)
                        .keySchema(
                                KeySchemaElement.builder()
                                        .attributeName("GSI3PK")
                                        .keyType(KeyType.HASH)
                                        .build(),
                                KeySchemaElement.builder()
                                        .attributeName("GSI3SK")
                                        .keyType(KeyType.RANGE)
                                        .build())
                        // ALL: the relay needs the payload; KEYS_ONLY would force a base-table
                        // GetItem per record, reinstating the read path this index removes.
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.ALL)
                                .build())
                        .build()));
        dynamoClient.waiter().waitUntilTableExists(request -> request.tableName(TABLE));
    }

    @BeforeEach
    void setUp() {
        outboxFactory = new CartOutboxRecordFactory("zylos-service-cart", "test", () -> "corr-it");
        repository = new DynamoCartRepository(enhanced, outboxFactory, TABLE);
        store = new OutboxStore(
                dynamoClient, enhanced, asyncClient, new ZylosDynamodbProperties(TABLE, "test-endpoint"));

        KafkaAvroSerializer serializer = new KafkaAvroSerializer(
                schemaRegistry, Map.of(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, SR_URL));

        KafkaProperties kafkaProps = new KafkaProperties();
        kafkaProps.setBootstrapServers(List.of(kafka.getBootstrapServers()));
        producers = new ShardProducerRegistry(kafkaProps, defaultCartProperties());

        CartEventPublisher publisher =
                new CartEventPublisher(producers, serializer, defaultCartProperties(), new SimpleMeterRegistry());

        relay = new OutboxRelay(store, publisher, defaultCartProperties(), new SimpleMeterRegistry());
        createTable();
    }

    @AfterEach
    void cleanupTable() {
        dynamoClient.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
        dynamoClient.waiter().waitUntilTableNotExists(request -> request.tableName(TABLE));
    }

    @Test
    void drainsTheOutboxToBothTopicsInOrderWithATerminalTombstone() {
        Cart cart = Cart.start(CartId.newId(), new CustomerOwner("customer-1"));
        cart.addLine(
                Sku.of("SKU-1"),
                Quantity.of(1),
                new PriceSnapshot(Money.ofMinor(1999, USD), 1L),
                "seller-1",
                LineOrigin.VALIDATED);

        repository.save(cart); // version 1, non-terminal
        Cart reloadedCart = repository.findById(cart.id()).orElseThrow();

        reloadedCart.markConverted("order-1");
        repository.save(reloadedCart); // version 2, terminal

        String cartId = cart.id().value().toString();

        relay.drainOnce();

        // Events topic: both events, in aggregate-version order, keyed by cartId.
        List<ConsumerRecord<String, byte[]>> events = consume(EVENTS_TOPIC, 2, cartId);
        assertThat(events).extracting(ConsumerRecord::key).containsOnly(cartId);
        assertThat(events)
                .extracting(record -> decode(EVENTS_TOPIC, record.value()).getEventType())
                .containsExactly("CartLineAdded", "CartConverted");
        assertThat(events)
                .extracting(record ->
                        decode(EVENTS_TOPIC, record.value()).getPayload().getVersion())
                .containsExactly(1L, 2L);

        // Snapshot topic: full state for v1, tombstone for the terminal v2.
        List<ConsumerRecord<String, byte[]>> snapshots = consume(SNAPSHOT_TOPIC, 2, cartId);
        assertThat(snapshots.get(0).value()).isNotNull();
        assertThat(decode(SNAPSHOT_TOPIC, snapshots.get(0).value()).getPayload().getVersion())
                .isEqualTo(1L);
        assertThat(snapshots.get(1).value()).isNull();
        assertThat(snapshots.get(1).key()).isEqualTo(cartId);
    }

    @Test
    void marksRecordsPublishedWithoutDeletingThem() {
        Cart cart = newCartWithOneLine();
        repository.save(cart);

        relay.drainOnce();
        consume(EVENTS_TOPIC, 1, cart.id().value().toString()); // wait for the published to land

        List<OutboxRecordItem> outboxItems = enhanced.table(TABLE, CartTableSchemas.OUTBOX).scan().items().stream()
                .filter(item -> item.pk().startsWith("OUTBOX#"))
                .toList();

        // The item survives (no delete -> no deleted-item accumulation in the hot partition) ...
        assertThat(outboxItems).singleElement().satisfies(item -> {
            assertThat(item.status()).isNull();
            assertThat(item.gsi3pk()).isNull();
            assertThat(item.gsi3sk()).isNull();
            assertThat(item.expiresAt()).isNotNull(); // TTL now owns cleanup
            assertThat(item.payload().asByteArray()).isNotEmpty();
        });

        // ... but it has left the sparse pending index.
        int shard = shardOf(cart);
        assertThat(store.readPending(shard, 100)).isEmpty();
    }

    @Test
    void isIdempotentAcrossRepeatedDrains() {
        Cart cart = newCartWithOneLine();
        repository.save(cart);
        int shard = shardOf(cart);

        relay.drainOnce();
        consume(EVENTS_TOPIC, 1, cart.id().value().toString());
        assertThat(store.readPending(shard, 100)).isEmpty();

        relay.drainOnce(); // nothing pending -> no second publish

        assertThat(consume(EVENTS_TOPIC, 1, cart.id().value().toString())).hasSize(1);
    }

    @Test
    void pendingRecordsCarryNoTtlSoTheyCannotBeReclaimedBeforePublication() {
        Cart cart = newCartWithOneLine();
        repository.save(cart);

        List<OutboxRecordItem> pending = store.readPending(shardOf(cart), 100);

        assertThat(pending).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("PENDING");
            assertThat(item.gsi3pk()).isEqualTo("PENDING#" + item.shardId());
            assertThat(item.gsi3sk()).isEqualTo(item.outboxId());
            assertThat(item.expiresAt()).isNull();
        });
    }

    @Test
    void routesAnUndecodableRecordToTheDlqWithoutBlockingTheShard() {
        Cart cart = newCartWithOneLine();
        repository.save(cart);
        int shard = shardOf(cart);

        OutboxRecordItem good = store.readPending(shard, 100).getFirst();
        OutboxRecordItem poison = OutboxRecordItem.builder()
                .pk("OUTBOX#" + shard)
                .sk("00000000-0000-7000-8000-000000000000") // sorts before the UUIDv7 good record
                .entityType("Outbox")
                .shardId(shard)
                .outboxId("00000000-0000-7000-8000-000000000000")
                .eventId("poison")
                .eventType("CartLineAdded")
                .aggregateId(good.aggregateId())
                .aggregateType("cart")
                .cartId(good.cartId())
                .occurredAt(Instant.now())
                .terminal(false)
                .payload(software.amazon.awssdk.core.SdkBytes.fromUtf8String("not-avro"))
                .gsi3pk("PENDING#" + shard)
                .gsi3sk("00000000-0000-7000-8000-000000000000")
                .status("PENDING")
                .expiresAt(null)
                .build();
        enhanced.table(TABLE, CartTableSchemas.OUTBOX).putItem(poison);

        relay.drainOnce();

        // The good record still published despite the poison sorting ahead of it.
        assertThat(consume(EVENTS_TOPIC, 1, cart.id().value().toString())).hasSize(1);
        assertThat(store.readPending(shard, 100)).isEmpty();

        List<OutboxRecordItem> dlq = enhanced.table(TABLE, CartTableSchemas.OUTBOX).scan().items().stream()
                .filter(item -> item.pk().startsWith("OUTBOXDLQ#"))
                .toList();
        assertThat(dlq)
                .singleElement()
                .satisfies(item -> assertThat(item.status()).isEqualTo("DEAD"));
    }

    @Test
    void aSecondClaimantFencesTheFirstViaTheProducerEpoch() {
        KafkaProperties kafkaProps = new KafkaProperties();
        kafkaProps.setBootstrapServers(List.of(kafka.getBootstrapServers()));

        ShardProducerRegistry firstClaimant = new ShardProducerRegistry(kafkaProps, defaultCartProperties());
        ShardProducerRegistry secondClaimant = new ShardProducerRegistry(kafkaProps, defaultCartProperties());
        try {
            Producer<String, byte[]> zombie = firstClaimant.producerFor(7);
            secondClaimant.producerFor(7); // bumps the epoch for cart-relay-shard-7

            zombie.beginTransaction();
            zombie.send(new ProducerRecord<>(FENCING_TOPIC, "cart-x", new byte[] {1}));

            // The broker rejects the stale epoch — the guarantee no lease deadline can provide.
            assertThatThrownBy(zombie::commitTransaction).isInstanceOf(InvalidProducerEpochException.class);
        } finally {
            firstClaimant.closeAll();
            secondClaimant.closeAll();
        }
    }

    @Test
    void aFencedShardIsAbandonedWithoutMarkingRecordsPublished() {
        Cart cart = newCartWithOneLine();
        repository.save(cart);
        int shard = shardOf(cart);

        KafkaProperties kafkaProps = new KafkaProperties();
        kafkaProps.setBootstrapServers(List.of(kafka.getBootstrapServers()));

        // Force our relay's producer to exist, then have a rival steal the epoch for that shard.
        producers.producerFor(shard);
        ShardProducerRegistry rival = new ShardProducerRegistry(kafkaProps, defaultCartProperties());
        try {
            rival.producerFor(shard);

            relay.drainOnce(); // must swallow the fencing and leave the work pending

            assertThat(store.readPending(shard, 100)).hasSize(1);
        } finally {
            rival.closeAll();
        }
    }
}
