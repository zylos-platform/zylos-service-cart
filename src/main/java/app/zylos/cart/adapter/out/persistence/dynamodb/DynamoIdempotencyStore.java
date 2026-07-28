package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import app.zylos.cart.application.port.out.IdempotencyStore;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * DynamoDB-backed idempotency store, sharing the cart single table.
 *
 * <p>A reservation is claimed with a conditional put that succeeds only when no live record exists.
 */
@Component
public class DynamoIdempotencyStore implements IdempotencyStore {

    private final DynamoDbClient client;
    private final DynamoDbTable<IdempotencyItem> table;
    private final String tableName;

    public DynamoIdempotencyStore(
            DynamoDbClient dynamoDbClient,
            DynamoDbEnhancedClient enhancedClient,
            @Value("${zylos.dynamodb.table-name:zylos-cart}") String tableName) {
        this.client = dynamoDbClient;
        this.tableName = tableName;
        this.table = enhancedClient.table(tableName, CartTableSchemas.IDEMPOTENCY);
    }

    private static String key(String idempotencyKey) {
        return "IDEM#" + idempotencyKey;
    }

    private static AttributeValue str(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue num(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    @Override
    public Reservation reserve(String idempotencyKey, String fingerprint, Duration inFlightTtl) {
        String pk = key(idempotencyKey);
        Instant now = Instant.now();

        try {
            client.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "PK", str(pk),
                            "SK", str(pk),
                            "entityType", str("Idempotency"),
                            "fingerprint", str(fingerprint),
                            "state", str(IdempotencyItem.IN_FLIGHT),
                            "expiresAt", num(now.plus(inFlightTtl).getEpochSecond())))
                    .conditionExpression("attribute_not_exists(PK) OR expiresAt < :now")
                    .expressionAttributeValues(Map.of(":now", num(now.getEpochSecond())))
                    .build());
            return new Reservation.Fresh();
        } catch (ConditionalCheckFailedException _) {
            return classifyExisting(pk, fingerprint);
        }
    }

    /**
     * A live record already holds the key; decide whether it is a replay, a race, or a misuse.
     */
    private Reservation classifyExisting(String pk, String fingerprint) {
        IdempotencyItem existing =
                table.getItem(Key.builder().partitionValue(pk).sortValue(pk).build());
        if (existing == null) {
            // Deleted between the failed condition and this read; treat as contended.
            return new Reservation.InFlight();
        }
        if (!existing.fingerprint().equals(fingerprint)) {
            return new Reservation.Conflict();
        }
        if (IdempotencyItem.COMPLETED.equals(existing.state()) && existing.responseStatus() != null) {
            byte[] body = existing.responseBody() == null
                    ? new byte[0]
                    : existing.responseBody().asByteArray();
            return new Reservation.Replay(existing.responseStatus(), existing.responseContentType(), body);
        }
        return new Reservation.InFlight();
    }

    @Override
    public void complete(
            String idempotencyKey,
            String fingerprint,
            int status,
            @Nullable String contentType,
            byte[] body,
            Duration ttl) {
        String pk = key(idempotencyKey);

        table.putItem(IdempotencyItem.builder()
                .pk(pk)
                .sk(pk)
                .fingerprint(fingerprint)
                .state(IdempotencyItem.COMPLETED)
                .responseStatus(status)
                .responseContentType(contentType)
                .responseBody(SdkBytes.fromByteArray(body))
                .expiresAt(Instant.now().plus(ttl).getEpochSecond())
                .build());
    }

    @Override
    public void release(String idempotencyKey) {
        String pk = key(idempotencyKey);
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("PK", str(pk), "SK", str(pk)))
                .conditionExpression("#state = :inFlight")
                .expressionAttributeNames(Map.of("#state", "state"))
                .expressionAttributeValues(Map.of(":inFlight", str(IdempotencyItem.IN_FLIGHT)))
                .build());
    }
}
