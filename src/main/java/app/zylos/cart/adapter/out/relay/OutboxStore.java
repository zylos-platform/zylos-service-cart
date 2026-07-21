package app.zylos.cart.adapter.out.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.springframework.stereotype.Component;

import app.zylos.cart.adapter.out.persistence.dynamodb.CartTableSchemas;
import app.zylos.cart.adapter.out.persistence.dynamodb.OutboxRecordItem;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

@Component
public class OutboxStore {

    private static final String OUTBOX_LEASE_PREFIX = "RELAYLEASE#";
    private static final String OUTBOX_PENDING_PREFIX = "PENDING#";
    private static final String OUTBOX_DLQ_PREFIX = "OUTBOXDLQ#";

    private final DynamoDbClient client;
    private final DynamoDbAsyncClient asyncClient;
    private final DynamoDbTable<OutboxRecordItem> outboxTable;
    private final DynamoDbIndex<OutboxRecordItem> pendingIndex;

    public OutboxStore(
            DynamoDbClient dynamoDbClient,
            DynamoDbEnhancedClient enhancedClient,
            DynamoDbAsyncClient asyncClient,
            ZylosDynamodbProperties dynamoDbProperties) {
        this.client = dynamoDbClient;
        this.asyncClient = asyncClient;
        this.outboxTable = enhancedClient.table(dynamoDbProperties.tableName(), CartTableSchemas.OUTBOX);
        this.pendingIndex = outboxTable.index(CartTableSchemas.GSI3_OUTBOX_PENDING);
    }

    private static AttributeValue str(String v) {
        return AttributeValue.fromS(v);
    }

    private static AttributeValue num(long v) {
        return AttributeValue.fromN(Long.toString(v));
    }

    public Optional<RelayLease> tryAcquireLease(int shard, String owner, Duration ttl, Instant now) {
        String pk = OUTBOX_LEASE_PREFIX + shard;
        Instant expiresAt = now.plus(ttl);

        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(outboxTable.tableName())
                    .key(Map.of("PK", str(pk), "SK", str(pk)))
                    .updateExpression("SET #owner = :owner, leaseExpiresAt = :exp, entityType = :etype")
                    .conditionExpression("attribute_not_exists(PK) OR leaseExpiresAt < :now")
                    .expressionAttributeNames(Map.of("#owner", "owner"))
                    .expressionAttributeValues(Map.of(
                            ":owner", str(owner),
                            ":exp", num(expiresAt.toEpochMilli()),
                            ":now", num(now.toEpochMilli()),
                            ":etype", str("RelayLease")))
                    .build());
            return Optional.of(new RelayLease(shard, owner, expiresAt));
        } catch (ConditionalCheckFailedException e) {
            return Optional.empty();
        }
    }

    /** Extends a lease we still own. Empty when it was taken over — the caller must stop draining. */
    public Optional<RelayLease> renewLease(RelayLease lease, Duration ttl, Instant now) {
        String pk = OUTBOX_LEASE_PREFIX + lease.shard();
        Instant expiresAt = now.plus(ttl);

        try {
            client.updateItem(UpdateItemRequest.builder()
                    .tableName(outboxTable.tableName())
                    .key(Map.of("PK", str(pk), "SK", str(pk)))
                    .updateExpression("SET leaseExpiresAt = :exp")
                    .conditionExpression("#owner = :owner")
                    .expressionAttributeNames(Map.of("#owner", "owner"))
                    .expressionAttributeValues(
                            Map.of(":owner", str(lease.owner()), ":exp", num(expiresAt.toEpochMilli())))
                    .build());
            return Optional.of(new RelayLease(lease.shard(), lease.owner(), expiresAt));
        } catch (ConditionalCheckFailedException _) {
            return Optional.empty();
        }
    }

    public void releaseLease(RelayLease lease) {
        String pk = OUTBOX_LEASE_PREFIX + lease.shard();

        try {
            client.deleteItem(DeleteItemRequest.builder()
                    .tableName(outboxTable.tableName())
                    .key(Map.of("PK", str(pk), "SK", str(pk)))
                    .conditionExpression("#owner = :owner")
                    .expressionAttributeNames(Map.of("#owner", "owner"))
                    .expressionAttributeValues(Map.of(":owner", str(lease.owner())))
                    .build());
        } catch (ConditionalCheckFailedException _) {
            // Lost the lease already; nothing to release.
        }
    }

    /**
     * Reads up to {@code limit} unpublished records for a shard from the sparse pending index,
     * ascending by {@code outboxId}.
     *
     * <p>The index is eventually consistent: a record written moments ago may not appear until the
     * next cycle. Sub-second, and harmless for an outbox.
     */
    public List<OutboxRecordItem> readPending(int shard, int limit) {
        QueryConditional byShard = QueryConditional.keyEqualTo(
                Key.builder().partitionValue(OUTBOX_PENDING_PREFIX + shard).build());
        return pendingIndex.query(r -> r.queryConditional(byShard).limit(limit)).stream()
                .flatMap(page -> page.items().stream())
                .limit(limit)
                .toList();
    }

    /**
     * Marks a record published: drops it from the sparse index (removing the index keys and status)
     * and sets {@code expiresAt} so TTL reclaims the item later. Deliberately not delete — that is
     * what accumulates deleted-item history in the hot partition.
     */
    public CompletableFuture<UpdateItemResponse> markPublishedAsync(
            OutboxRecordItem item, Duration retention, Instant now) {
        return asyncClient.updateItem(UpdateItemRequest.builder()
                .tableName(outboxTable.tableName())
                .key(Map.of("PK", str(item.pk()), "SK", str(item.sk())))
                .updateExpression("REMOVE GSI3PK, GSI3SK, #status SET expiresAt = :exp")
                .conditionExpression("attribute_exists(PK)")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(
                        Map.of(":exp", num(now.plus(retention).getEpochSecond())))
                .build());
    }

    public void markPublished(OutboxRecordItem item, Duration retention, Instant now) {
        client.updateItem(UpdateItemRequest.builder()
                .tableName(outboxTable.tableName())
                .key(Map.of("PK", str(item.pk()), "SK", str(item.sk())))
                .updateExpression("REMOVE GSI3PK, GSI3SK, #status SET expiresAt = :exp")
                .conditionExpression("attribute_exists(PK)")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(
                        Map.of(":exp", num(now.plus(retention).getEpochSecond())))
                .build());
    }

    /** Moves a poison record to the shard's dead-letter partition and clears it from the index. */
    public void moveToDlq(OutboxRecordItem item, Duration retention, Instant now) {
        OutboxRecordItem dead = OutboxRecordItem.builder()
                .pk(OUTBOX_DLQ_PREFIX + item.shardId())
                .sk(item.sk())
                .entityType("OutboxDlq")
                .shardId(item.shardId())
                .outboxId(item.outboxId())
                .eventId(item.eventId())
                .eventType(item.eventType())
                .aggregateId(item.aggregateId())
                .aggregateType(item.aggregateType())
                .cartId(item.cartId())
                .occurredAt(item.occurredAt())
                .terminal(item.terminal())
                .payload(item.payload())
                .gsi3pk(null)
                .gsi3sk(null)
                .status("DEAD")
                .expiresAt(null) // poison is retained for investigation
                .build();
        outboxTable.putItem(dead);
        markPublished(item, retention, now);
    }
}
