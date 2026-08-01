package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.net.URI;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Testcontainers
public abstract class AbstractCartTableIT {

    protected static final String TABLE = "zylos-cart";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> dynamo = new GenericContainer<>(
                    DockerImageName.parse("amazon/dynamodb-local:3.2.0"))
            .withCommand("-jar DynamoDBLocal.jar -inMemory -sharedDb")
            .withExposedPorts(8000);

    protected static DynamoDbClient client;
    protected static DynamoDbEnhancedClient enhancedClient;

    @BeforeAll
    static void startClient() {
        String endpoint = "http://%s:%d".formatted(dynamo.getHost(), dynamo.getMappedPort(8000));
        client = DynamoDbClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .build();
        enhancedClient = DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @AfterAll
    static void stopClient() {
        if (client != null) {
            client.close();
        }
    }

    @BeforeEach
    void createTable() {
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
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("GSI2PK")
                                .attributeType(ScalarAttributeType.S)
                                .build(),
                        AttributeDefinition.builder()
                                .attributeName("GSI2SK")
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
                .globalSecondaryIndexes(
                        // GSI 1: Expiry
                        GlobalSecondaryIndex.builder()
                                .indexName(CartTableSchemas.GSI1_EXPIRY)
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
                                .build(),

                        // GSI 2: Outbox
                        GlobalSecondaryIndex.builder()
                                .indexName(CartTableSchemas.GSI2_OUTBOX_PENDING)
                                .keySchema(
                                        KeySchemaElement.builder()
                                                .attributeName("GSI2PK")
                                                .keyType(KeyType.HASH)
                                                .build(),
                                        KeySchemaElement.builder()
                                                .attributeName("GSI2SK")
                                                .keyType(KeyType.RANGE)
                                                .build())
                                .projection(Projection.builder()
                                        .projectionType(ProjectionType.ALL)
                                        .build())
                                .build()));

        client.waiter().waitUntilTableExists(r -> r.tableName(TABLE));
    }

    @AfterEach
    void dropTable() {
        client.deleteTable(b -> b.tableName(TABLE));
    }
}
