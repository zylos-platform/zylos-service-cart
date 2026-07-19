package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import app.zylos.cart.application.port.out.CartRepository;
import app.zylos.cart.application.port.out.OptimisticConcurrencyException;
import app.zylos.cart.domain.event.DomainEvent;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.vo.CartId;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactPutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * DynamoDB adapter implementing {@link CartRepository}. A cart and the events it recorded are written
 * atomically in one {@code TransactWriteItems}: a conditional put of the cart item (guarded on the
 * loaded version for optimistic concurrency) plus a put per outbox record. There is no dual-write.
 */
@Repository
public class DynamoCartRepository implements CartRepository {

    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbTable<CartItem> cartTable;
    private final DynamoDbTable<OutboxRecordItem> outboxTable;
    private final DynamoDbEnhancedClient enhancedClient;
    private final CartOutboxRecordFactory outboxFactory;

    public DynamoCartRepository(
            DynamoDbEnhancedClient enhancedClient,
            CartOutboxRecordFactory outboxFactory,
            @Value("${zylos.dynamodb.table-name:zylos-cart}") String tableName) {
        this.enhancedClient = enhancedClient;
        this.outboxFactory = outboxFactory;
        this.cartTable = enhancedClient.table(tableName, CartTableSchemas.CART);
        this.outboxTable = enhancedClient.table(tableName, CartTableSchemas.OUTBOX);
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

    @Override
    public void save(Cart cart) {
        Instant now = Instant.now();
        List<DomainEvent> events = cart.pullDomainEvents();
        CartItem cartItem = CartItemMapper.toItem(cart, now);
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

    @Override
    public Optional<Cart> findById(CartId cartId) {
        String pk = CartItemMapper.cartPk(cartId);
        CartItem item =
                cartTable.getItem(Key.builder().partitionValue(pk).sortValue(pk).build());
        return Optional.of(item).map(CartItemMapper::toDomain);
    }
}
