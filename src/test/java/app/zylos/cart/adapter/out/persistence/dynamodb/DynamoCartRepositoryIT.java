package app.zylos.cart.adapter.out.persistence.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import app.zylos.cart.adapter.out.relay.ZylosDynamodbProperties;
import app.zylos.cart.application.port.out.OptimisticConcurrencyException;
import app.zylos.cart.config.ZylosCartExpiryProperties;
import app.zylos.cart.config.ZylosCartOutboxProperties;
import app.zylos.cart.config.ZylosCartServiceProperties;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner.CustomerOwner;
import app.zylos.cart.domain.vo.*;
import app.zylos.contracts.cart.v1.CartEvent;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;

@Testcontainers
class DynamoCartRepositoryIT extends AbstractCartTableIT {

    private static final Currency USD = Currency.getInstance("USD");

    private DynamoCartRepository repository;
    private DynamoDbTable<CartItem> cartTable;
    private DynamoDbTable<OutboxRecordItem> outboxTable;

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

    @BeforeEach
    void setUpRepository() {
        var outboxFactory = new CartOutboxRecordFactory(
                new ZylosCartServiceProperties("zylos-service-cart", "0.0.0"),
                new ZylosCartOutboxProperties(16, 16),
                () -> "corr-123");

        this.repository = new DynamoCartRepository(
                client,
                enhancedClient,
                outboxFactory,
                new ZylosDynamodbProperties(TABLE, "test-endpoint"),
                new ZylosCartExpiryProperties(8, 8));
        this.cartTable = enhancedClient.table(TABLE, CartTableSchemas.CART);
        this.outboxTable = enhancedClient.table(TABLE, CartTableSchemas.OUTBOX);
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
