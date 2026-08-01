package app.zylos.cart.adapter.out.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import app.zylos.cart.adapter.out.catalog.cache.CachingCatalogClient;
import app.zylos.cart.adapter.out.catalog.cache.CatalogCacheEntry;
import app.zylos.cart.adapter.out.catalog.grpc.CatalogUnavailableException;
import app.zylos.cart.adapter.out.catalog.grpc.GrpcCatalogClient;
import app.zylos.cart.adapter.out.catalog.resilience.ResilientCatalogClient;
import app.zylos.cart.adapter.out.persistence.dynamodb.CartOutboxRecordFactory;
import app.zylos.cart.adapter.out.persistence.dynamodb.CartTableSchemas;
import app.zylos.cart.adapter.out.persistence.dynamodb.DynamoCartRepository;
import app.zylos.cart.adapter.out.relay.ZylosDynamodbProperties;
import app.zylos.cart.adapter.out.security.OpaCartAuthorization;
import app.zylos.cart.application.command.AddLineToCartCommand;
import app.zylos.cart.application.port.out.OptimisticConcurrencyException;
import app.zylos.cart.application.service.CartCommandService;
import app.zylos.cart.config.*;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.vo.LineOrigin;
import app.zylos.cart.domain.vo.Quantity;
import app.zylos.cart.domain.vo.Sku;
import app.zylos.contracts.zylos.catalog.v1.GetVariantBySkuRequest;
import app.zylos.contracts.zylos.catalog.v1.GetVariantBySkuResponse;
import app.zylos.contracts.zylos.catalog.v1.ProductServiceGrpc;
import app.zylos.contracts.zylos.catalog.v1.VariantSnapshot;
import app.zylos.contracts.zylos.common.v1.Money;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.grpc.*;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@org.testcontainers.junit.jupiter.Testcontainers
class CartDegradedAcceptIT {

    static final Network network = Network.newNetwork();
    static final ToxiproxyContainer toxiproxy =
            new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0")).withNetwork(network);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> dynamo = new GenericContainer<>(
                    DockerImageName.parse("amazon/dynamodb-local:3.2.0"))
            .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb")
            .withExposedPorts(8000);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> valkey =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:8-alpine")).withExposedPorts(6379);

    private static final Sku SKU = Sku.of("SKU-1");
    private static final CartOwner OWNER = new CartOwner.CustomerOwner("customer-1");
    private static final String TABLE = "zylos-cart";
    private static final int TOXIPROXY_PORT = 8666;
    private static Server fakeCatalog;
    private static FakeCatalogService catalogService;
    private static Proxy catalogProxy;
    private static ManagedChannel channel;

    private static DynamoDbClient dynamoClient;
    private static DynamoDbEnhancedClient enhanced;
    private static RedisTemplate<String, CatalogCacheEntry> cacheTemplate;

    private CartCommandService service;
    private DynamoCartRepository repository;
    private SimpleMeterRegistry registry;

    private CircuitBreaker circuitBreaker;

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
                        .indexName(CartTableSchemas.GSI2_OUTBOX_PENDING)
                        .keySchema(
                                KeySchemaElement.builder()
                                        .attributeName("GSI3PK")
                                        .keyType(KeyType.HASH)
                                        .build(),
                                KeySchemaElement.builder()
                                        .attributeName("GSI3SK")
                                        .keyType(KeyType.RANGE)
                                        .build())
                        .projection(Projection.builder()
                                .projectionType(ProjectionType.ALL)
                                .build())
                        .build()));
        dynamoClient.waiter().waitUntilTableExists(request -> request.tableName(TABLE));
    }

    @BeforeAll
    static void startStack() throws Exception {
        // A real gRPC Catalog on the host, reachable from Toxiproxy via host.testcontainers.internal.
        catalogService = new FakeCatalogService();
        fakeCatalog =
                ServerBuilder.forPort(0).addService(catalogService).build().start();
        int catalogPort = fakeCatalog.getPort();

        Testcontainers.exposeHostPorts(catalogPort);
        toxiproxy.start();

        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
        catalogProxy = toxiproxyClient.createProxy(
                "catalog", "0.0.0.0:" + TOXIPROXY_PORT, "host.testcontainers.internal:" + catalogPort);

        channel = ManagedChannelBuilder.forAddress(toxiproxy.getHost(), toxiproxy.getMappedPort(TOXIPROXY_PORT))
                .usePlaintext()
                .build();

        dynamoClient = DynamoDbClient.builder()
                .endpointOverride(URI.create("http://%s:%d".formatted(dynamo.getHost(), dynamo.getMappedPort(8000))))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        enhanced = DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoClient).build();

        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(valkey.getHost(), valkey.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        cacheTemplate = new RedisTemplate<>();
        cacheTemplate.setConnectionFactory(connectionFactory);
        cacheTemplate.setKeySerializer(RedisSerializer.string());
        cacheTemplate.setValueSerializer(new JacksonJsonRedisSerializer<>(CatalogCacheEntry.class));
        cacheTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void stopStack() {
        channel.shutdownNow();
        fakeCatalog.shutdownNow();
        dynamoClient.close();
        toxiproxy.stop();
    }

    @BeforeEach
    void setUp() throws IOException {
        catalogProxy.enable();
        catalogService.reset();
        cacheTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        registry = new SimpleMeterRegistry();
        var factory = new CartOutboxRecordFactory(
                new ZylosCartServiceProperties("zylos-service-cart", "0.0.0"),
                new ZylosCartOutboxProperties(16, 16),
                () -> "corr-it");
        repository = new DynamoCartRepository(
                dynamoClient,
                enhanced,
                factory,
                new ZylosDynamodbProperties(TABLE, "test-endpoint"),
                new ZylosCartExpiryProperties(8, 8));

        GrpcCatalogClient grpc = new GrpcCatalogClient(
                ProductServiceGrpc.newBlockingStub(channel), new ZylosCatalogGrpcProperties(2000, "s2s-id"));

        this.circuitBreaker = CircuitBreaker.of(
                "catalog",
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(2)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(30))
                        .recordExceptions(CatalogUnavailableException.class)
                        .build());

        ResilientCatalogClient resilient = new ResilientCatalogClient(
                grpc,
                this.circuitBreaker,
                Bulkhead.of(
                        "catalog", BulkheadConfig.custom().maxConcurrentCalls(8).build()),
                Retry.of(
                        "catalog",
                        RetryConfig.custom()
                                .maxAttempts(2)
                                .waitDuration(Duration.ofMillis(10))
                                .retryExceptions(CatalogUnavailableException.class)
                                .build()));

        ZylosCatalogCacheProperties props = new ZylosCatalogCacheProperties("it:sku:", 60, 3600, 10, 60);
        CachingCatalogClient caching = new CachingCatalogClient(resilient, cacheTemplate, props, registry);

        Retry retry = Retry.of(
                "cart-optimistic",
                RetryConfig.custom()
                        .maxAttempts(4)
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(5L), 2.0, 0.5))
                        .retryExceptions(OptimisticConcurrencyException.class)
                        .failAfterMaxAttempts(true)
                        .build());

        service = new CartCommandService(
                repository, caching, () -> OWNER, mock(OpaCartAuthorization.class), retry, registry);
        createTable();
    }

    @AfterEach
    void cleanupTable() {
        dynamoClient.deleteTable(DeleteTableRequest.builder().tableName(TABLE).build());
        dynamoClient.waiter().waitUntilTableNotExists(request -> request.tableName(TABLE));
    }

    @Test
    void withCatalogHealthyTheLineIsValidatedAndPriced() {
        service.addLine(new AddLineToCartCommand(SKU, Quantity.of(2)));

        Cart cart = repository.findActiveByOwner(OWNER).orElseThrow();
        assertThat(cart.lines()).singleElement().satisfies(line -> {
            assertThat(line.origin()).isEqualTo(LineOrigin.VALIDATED);
            assertThat(line.priceSnapshot().unitPrice().minorUnits()).isEqualTo(1999L);
        });
    }

    @Test
    void withCatalogFullyDeadTheCartIsStillWritable() throws IOException {
        catalogProxy.disable(); // Catalog is unreachable at the network

        service.addLine(new AddLineToCartCommand(SKU, Quantity.of(1))); // must not throw

        Cart cart = repository.findActiveByOwner(OWNER).orElseThrow();
        assertThat(cart.lines()).singleElement().satisfies(line -> {
            assertThat(line.origin()).isEqualTo(LineOrigin.VALIDATION_PENDING);
            assertThat(line.priceSnapshot()).isNull();
        });
        assertThat(registry.get("zylos.cart.degraded_accept").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aWarmCacheSurvivesCatalogDeathWithAPricedLine() throws IOException {
        service.addLine(new AddLineToCartCommand(SKU, Quantity.of(1))); // warms the cache
        catalogProxy.disable();

        service.addLine(new AddLineToCartCommand(SKU, Quantity.of(1)));

        Cart cart = repository.findActiveByOwner(OWNER).orElseThrow();
        // Served from cache: still VALIDATED and priced despite Catalog being unreachable.
        assertThat(cart.lines()).singleElement().satisfies(line -> {
            assertThat(line.origin()).isEqualTo(LineOrigin.VALIDATED);
            assertThat(line.quantity()).isEqualTo(Quantity.of(2));
        });
    }

    @Test
    void theCartRecoversToValidatedOnceCatalogReturns() throws IOException {
        catalogProxy.disable();
        service.addLine(new AddLineToCartCommand(SKU, Quantity.of(1)));
        catalogProxy.enable();

        Awaitility.await("Wait for gRPC channel to recover")
                .atMost(Duration.ofSeconds(5)) // Safety net timeout
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    ConnectivityState state = channel.getState(true);
                    return state == ConnectivityState.READY;
                });

        circuitBreaker.transitionToHalfOpenState();

        service.addLine(new AddLineToCartCommand(Sku.of("SKU-2"), Quantity.of(1)));

        Cart cart = repository.findActiveByOwner(OWNER).orElseThrow();
        assertThat(cart.lines()).hasSize(2);
        assertThat(cart.lines()).anySatisfy(line -> assertThat(line.origin()).isEqualTo(LineOrigin.VALIDATED));
    }

    private static final class FakeCatalogService extends ProductServiceGrpc.ProductServiceImplBase {

        void reset() {
            // stateless; present for symmetry with richer fakes
        }

        @Override
        public void getVariantBySku(GetVariantBySkuRequest request, StreamObserver<GetVariantBySkuResponse> obs) {
            obs.onNext(GetVariantBySkuResponse.newBuilder()
                    .setVariant(VariantSnapshot.newBuilder()
                            .setSku(request.getSku())
                            .setProductId("product-1")
                            .setVariantId("variant-1")
                            .setSellerId("seller-1")
                            .setPurchasable(true)
                            .setCatalogVersion(7)
                            .setListPrice(Money.newBuilder().setMinorUnits(1999).setCurrencyCode("USD")))
                    .build());
            obs.onCompleted();
        }
    }
}
