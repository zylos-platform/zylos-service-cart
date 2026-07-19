package app.zylos.cart.adapter.out.persistence.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import app.zylos.cart.application.port.out.OptimisticConcurrencyException;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner.CustomerOwner;
import app.zylos.cart.domain.vo.*;
import app.zylos.contracts.cart.v1.CartEvent;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Testcontainers
class DynamoCartRepositoryIT {

    @Container
    static final GenericContainer<?> dynamo = new GenericContainer<>(
                    DockerImageName.parse("amazon/dynamodb-local:3.2.0"))
            .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb")
            .withExposedPorts(8000);

    private static final Currency USD = Currency.getInstance("USD");
    private static final String TABLE = "zylos-cart";
    private static DynamoDbClient client;
    private static DynamoDbEnhancedClient enhanced;
    private DynamoCartRepository repository;
    private DynamoDbTable<CartItem> cartTable;
    private DynamoDbTable<OutboxRecordItem> outboxTable;

    @BeforeAll
    static void startClient() {
        String endpoint = "http://%s:%d".formatted(dynamo.getHost(), dynamo.getMappedPort(8000));
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @AfterAll
    static void stopClient() {
        client.close();
    }

    private static Cart cartWithOneLine() {
        Cart cart = Cart.start(CartId.newId(), new CustomerOwner("customer-123"));
        cart.addLine(
                Sku.of("SKU-1"),
                Quantity.of(2),
                new PriceSnapshot(Money.ofMinor(1999, USD), 1L),
                "seller-1",
                LineOrigin.VALIDATED);
        return cart;
    }

    private static void createTable() {
        client.createTable(b -> b.tableName(TABLE)
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
                                .attributeName("GSI1PK")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("GSI1SK")
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
                        .indexName(CartTableSchemas.GSI1_OWNER)
                        .keySchema(
                                KeySchemaElement.builder()
                                        .attributeName("GSI1PK")
                                        .keyType(KeyType.HASH)
                                        .build(),
                                KeySchemaElement.builder()
                                        .attributeName("GSI1SK")
                                        .keyType(KeyType.RANGE)
                                        .build())
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.KEYS_ONLY)
                                .build())
                        .build()));
        client.waiter().waitUntilTableExists(r -> r.tableName(TABLE));
    }

    @BeforeEach
    void setUp() {
        createTable();
        CartOutboxRecordFactory outboxFactory =
                new CartOutboxRecordFactory("zylos-service-cart", "test", () -> "corr-123");
        repository = new DynamoCartRepository(enhanced, outboxFactory, TABLE);
        cartTable = enhanced.table(TABLE, CartTableSchemas.CART);
        outboxTable = enhanced.table(TABLE, CartTableSchemas.OUTBOX);
    }

    @AfterEach
    void tearDown() {
        client.deleteTable(b -> b.tableName(TABLE));
    }

    @Test
    void savePersistsCartAndOutboxRecordAtomically() throws Exception {
        Cart cart = cartWithOneLine();
        repository.save(cart);

        String pk = "CART#" + cart.id().value();
        assertThat(cartTable.getItem(
                        Key.builder().partitionValue(pk).sortValue(pk).build()))
                .isNotNull()
                .satisfies(item -> {
                    assertThat(item.version()).isEqualTo(1L);
                    assertThat(item.lines()).hasSize(1);
                    assertThat(item.currency()).isEqualTo("USD");
                });

        List<OutboxRecordItem> outbox = outboxTable.scan().items().stream()
                .filter(item -> item.pk().startsWith("OUTBOX#"))
                .toList();

        assertThat(outbox).singleElement().satisfies(recordItem -> {
            assertThat(recordItem.eventType()).isEqualTo("CartLineAdded");
            assertThat(recordItem.aggregateType()).isEqualTo("cart");
            assertThat(recordItem.payload().asByteArray()).isNotEmpty();
        });

        // The stored payload is registry-free single-object Avro: it decodes with only the schema.
        CartEvent decoded = CartEvent.fromByteBuffer(outbox.getFirst().payload().asByteBuffer());
        assertThat(decoded.getEventType()).isEqualTo("CartLineAdded");
        assertThat(decoded.getPayload().getVersion()).isEqualTo(1L);
    }

    @Test
    void findByIdReconstitutesTheAggregate() {
        Cart cart = cartWithOneLine();
        repository.save(cart);

        Cart loaded = repository.findById(cart.id()).orElseThrow();
        assertThat(loaded.version()).isEqualTo(1L);
        assertThat(loaded.currency()).isEqualTo(USD);
        assertThat(loaded.lines()).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo(Sku.of("SKU-1"));
            assertThat(l.quantity()).isEqualTo(Quantity.of(2));
        });
        assertThat(loaded.pullDomainEvents()).isEmpty();
    }

    @Test
    void concurrentWriteLosesTheOptimisticRace() {
        Cart cart = cartWithOneLine();
        repository.save(cart); // now at version 1 in the store

        Cart writerA = repository.findById(cart.id()).orElseThrow(); // baseVersion 1
        Cart writerB = repository.findById(cart.id()).orElseThrow(); // baseVersion 1

        writerA.addLine(
                Sku.of("SKU-2"),
                Quantity.of(1),
                new PriceSnapshot(Money.ofMinor(500, USD), 1L),
                "seller-1",
                LineOrigin.VALIDATED);
        repository.save(writerA); // succeeds -> store at version 2

        writerB.changeLineQuantity(Sku.of("SKU-1"), Quantity.of(9));
        assertThatThrownBy(() -> repository.save(writerB)) // still conditions on version 1
                .isInstanceOf(OptimisticConcurrencyException.class);
    }

    @Test
    void aRejectedTransactionWritesNoOutboxRecord() {
        Cart cart = cartWithOneLine();
        repository.save(cart);

        // A second "new cart" insert on the same id fails attribute_not_exists — the whole
        // transaction, including its outbox put, must be canceled.
        Cart duplicate = Cart.reconstitute(
                cart.id(), new CustomerOwner("customer-123"), cart.status(), USD, cart.lines(), null, null, 1L);
        // force baseVersion 0 path by starting fresh with the same id
        Cart sameIdNew = Cart.start(cart.id(), new CustomerOwner("customer-123"));
        sameIdNew.addLine(
                Sku.of("SKU-9"),
                Quantity.of(1),
                new PriceSnapshot(Money.ofMinor(100, USD), 1L),
                "seller-1",
                LineOrigin.VALIDATED);

        assertThatThrownBy(() -> repository.save(sameIdNew)).isInstanceOf(OptimisticConcurrencyException.class);

        long outboxCount = outboxTable.scan().items().stream()
                .filter(item -> item.pk().startsWith("OUTBOX#"))
                .count();
        assertThat(outboxCount).isEqualTo(1); // only the original CartLineAdded, no leak
        assertThat(duplicate.version()).isEqualTo(1L); // (unused guard object)
    }
}
