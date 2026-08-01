package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import app.zylos.cart.adapter.out.relay.ZylosDynamodbProperties;
import app.zylos.cart.application.port.out.CartRepository;
import app.zylos.cart.application.port.out.OptimisticConcurrencyException;
import app.zylos.cart.config.ZylosCartExpiryProperties;
import app.zylos.cart.domain.event.DomainEvent;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.model.CartStatus;
import app.zylos.cart.domain.vo.CartId;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactDeleteItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/**
 * DynamoDB adapter implementing {@link CartRepository}. A cart and the events it recorded are written
 * atomically in one {@code TransactWriteItems}: a conditional put of the cart item (guarded on the
 * loaded version for optimistic concurrency) plus a put per outbox record. There is no dual-write.
 */
@Repository
public class DynamoCartRepository implements CartRepository {

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbClient dynamoDbClient;
    private final DynamoDbEnhancedClient enhancedClient;
    private final CartOutboxRecordFactory outboxFactory;

    private final DynamoDbTable<CartItem> cartTable;
    private final DynamoDbTable<OutboxRecordItem> outboxTable;
    private final DynamoDbTable<ActiveCartPointerItem> pointerTable;

    private final ZylosCartExpiryProperties expiryProperties;

    public DynamoCartRepository(
            DynamoDbClient dynamoDbClient,
            DynamoDbEnhancedClient enhancedClient,
            CartOutboxRecordFactory outboxFactory,
            ZylosDynamodbProperties properties,
            ZylosCartExpiryProperties expiryProperties) {
        this.dynamoDbClient = dynamoDbClient;
        this.enhancedClient = enhancedClient;
        this.outboxFactory = outboxFactory;

        String tableName = properties.tableName();
        this.cartTable = enhancedClient.table(tableName, CartTableSchemas.CART);
        this.outboxTable = enhancedClient.table(tableName, CartTableSchemas.OUTBOX);
        this.pointerTable = enhancedClient.table(tableName, CartTableSchemas.ACTIVE_CART_POINTER);
        this.expiryProperties = expiryProperties;
    }

    private static Expression versionGuard(long baseVersion) {
        if (baseVersion == 0L) {
            // First persist: succeed only if the item does not yet exist.
            return Expression.builder()
                    .expression("attribute_not_exists(#pk)")
                    .putExpressionName("#pk", "PK")
                    .build();
        }
        // Update: succeed only if the stored version still matches the one we loaded.
        return Expression.builder()
                .expression("#version = :expected")
                .putExpressionName("#version", "version")
                .putExpressionValue(":expected", AttributeValue.fromN(Long.toString(baseVersion)))
                .build();
    }

    private static boolean isConditionalCheckFailure(TransactionCanceledException e) {
        for (CancellationReason reason : e.cancellationReasons()) {
            if (CONDITIONAL_CHECK_FAILED.equals(reason.code())) {
                return true;
            }
        }
        return false;
    }

    private static String pointerPk(CartOwner owner) {
        String ownerType = owner instanceof CartOwner.CustomerOwner ? "CUSTOMER" : "GUEST";
        return "OWNER#" + ownerType + "#" + owner.subjectId() + "#ACTIVE";
    }

    @Override
    public void save(Cart cart) {
        Instant now = Instant.now();
        List<DomainEvent> events = cart.pullDomainEvents();
        CartItem cartItem = CartItemMapper.toItem(cart, now, expiryProperties.writeShards());
        List<OutboxRecordItem> outboxItems = outboxFactory.from(cart, events, now);

        TransactWriteItemsEnhancedRequest.Builder tx = TransactWriteItemsEnhancedRequest.builder();
        tx.addPutItem(
                cartTable,
                TransactPutItemEnhancedRequest.builder(CartItem.class)
                        .item(cartItem)
                        .conditionExpression(versionGuard(cart.baseVersion()))
                        .build());

        for (OutboxRecordItem outboxItem : outboxItems) {
            tx.addPutItem(outboxTable, outboxItem);
        }

        if (cart.baseVersion() == 0L) {
            String ownerPointerId = pointerPk(cart.owner());
            tx.addPutItem(
                    pointerTable,
                    TransactPutItemEnhancedRequest.builder(ActiveCartPointerItem.class)
                            .item(new ActiveCartPointerItem(
                                    ownerPointerId,
                                    ownerPointerId,
                                    cart.id().value().toString()))
                            .conditionExpression(Expression.builder()
                                    .expression("attribute_not_exists(#pk)")
                                    .putExpressionName("#pk", "PK")
                                    .build())
                            .build());
        } else if (cart.status() != CartStatus.ACTIVE) {
            String ownerPointerId = pointerPk(cart.owner());
            tx.addDeleteItem(
                    pointerTable,
                    TransactDeleteItemEnhancedRequest.builder()
                            .key(Key.builder()
                                    .partitionValue(ownerPointerId)
                                    .sortValue(ownerPointerId)
                                    .build())
                            .build());
        }

        try {
            enhancedClient.transactWriteItems(tx.build());
        } catch (TransactionCanceledException e) {
            if (isConditionalCheckFailure(e)) {
                throw new OptimisticConcurrencyException(
                        "Cart %s was modified concurrently (expected version %d)"
                                .formatted(cart.id(), cart.baseVersion()),
                        e);
            }
            throw e;
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public Optional<Cart> findById(CartId cartId) {
        String pk = CartItemMapper.cartPk(cartId);
        CartItem item =
                cartTable.getItem(Key.builder().partitionValue(pk).sortValue(pk).build());
        return Optional.ofNullable(item).map(CartItemMapper::toDomain);
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public Optional<Cart> findActiveByOwner(CartOwner owner) {
        String ownerPointerId = pointerPk(owner);
        Key key = Key.builder()
                .partitionValue(ownerPointerId)
                .sortValue(ownerPointerId)
                .build();

        return Optional.ofNullable(pointerTable.getItem(key)).flatMap(pointer -> findById(CartId.of(pointer.cartId())));
    }

    @Override
    public boolean expire(Cart cart) {
        Instant now = Instant.now();
        List<DomainEvent> events = cart.pullDomainEvents();
        List<OutboxRecordItem> outboxItems = outboxFactory.from(cart, events, now);
        String pk = CartItemMapper.cartPk(cart.id());

        TransactWriteItemsEnhancedRequest.Builder tx = TransactWriteItemsEnhancedRequest.builder();
        tx.addDeleteItem(
                cartTable,
                TransactDeleteItemEnhancedRequest.builder()
                        .key(Key.builder().partitionValue(pk).sortValue(pk).build())
                        // The cart must still be exactly as the sweep found it.
                        .conditionExpression(Expression.builder()
                                .expression("#version = :expected")
                                .putExpressionName("#version", "version")
                                .putExpressionValue(
                                        ":expected", AttributeValue.fromN(Long.toString(cart.baseVersion())))
                                .build())
                        .build());

        if (cart.status() == CartStatus.ACTIVE) {
            String ownerPointerId = pointerPk(cart.owner());
            tx.addDeleteItem(
                    pointerTable,
                    TransactDeleteItemEnhancedRequest.builder()
                            .key(Key.builder()
                                    .partitionValue(ownerPointerId)
                                    .sortValue(ownerPointerId)
                                    .build())
                            .conditionExpression(Expression.builder()
                                    .expression("attribute_not_exists(#pk) OR #cartId = :expectedCartId")
                                    .putExpressionName("#pk", "PK")
                                    .putExpressionName("#cartId", "cartId")
                                    .putExpressionValue(
                                            ":expectedCartId",
                                            AttributeValue.fromS(
                                                    cart.id().value().toString()))
                                    .build())
                            .build());
        }

        for (OutboxRecordItem outboxItem : outboxItems) {
            tx.addPutItem(outboxTable, outboxItem);
        }

        try {
            enhancedClient.transactWriteItems(tx.build());
            return true;
        } catch (TransactionCanceledException e) {
            if (isConditionalCheckFailure(e)) {
                return false; // lost the race; the cart lives on
            }
            throw e;
        }
    }

    @Override
    public void moveToDeadLetter(CartId cartId) {
        String pk = CartItemMapper.cartPk(cartId);

        UpdateItemRequest updateRequest = UpdateItemRequest.builder()
                .tableName(cartTable.tableName())
                .key(Map.of(
                        "PK", AttributeValue.builder().s(pk).build(),
                        "SK", AttributeValue.builder().s(pk).build()))
                .updateExpression("REMOVE GSI1PK, GSI1SK, expiresAt SET #status = :poisonStatus")
                .expressionAttributeNames(Map.of("#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":poisonStatus", AttributeValue.builder().s("DEAD").build()))
                .build();

        dynamoDbClient.updateItem(updateRequest);
    }
}
