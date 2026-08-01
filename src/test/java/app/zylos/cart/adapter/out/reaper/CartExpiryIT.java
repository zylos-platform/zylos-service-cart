package app.zylos.cart.adapter.out.reaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zylos.cart.adapter.out.persistence.dynamodb.*;
import app.zylos.cart.adapter.out.relay.ZylosDynamodbProperties;
import app.zylos.cart.application.port.out.CartRepository;
import app.zylos.cart.config.ZylosCartExpiryProperties;
import app.zylos.cart.config.ZylosCartOutboxProperties;
import app.zylos.cart.config.ZylosCartServiceProperties;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.vo.*;
import app.zylos.contracts.cart.v1.CartEvent;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;

/**
 * Verifies the expiry path against dynamodb-local: the sharded expiry index is actually populated, the
 * sweep finds an elapsed cart, and the deletion plus its tombstone-bearing outbox record commit
 * atomically. Extends the shared cart-table IT base.
 */
class CartExpiryIT extends AbstractCartTableIT {

    private static final Currency USD = Currency.getInstance("USD");

    private CartRepository repository;
    private ExpiredCartScanner scanner;
    private DynamoDbIndex<OutboxRecordItem> pendingIndex;
    private int testOutboxShards;

    private static Cart newValidatedCart() {
        Cart cart = Cart.start(CartId.newId(), new CartOwner.CustomerOwner("customer-123"));
        cart.addLine(
                Sku.of("SKU-1"),
                Quantity.of(2),
                new PriceSnapshot(Money.ofMinor(1999, USD), 1L),
                "seller-1",
                LineOrigin.VALIDATED);
        return cart;
    }

    public static PriceSnapshot usd(long minor) {
        return new PriceSnapshot(Money.ofMinor(minor, USD), 1L);
    }

    @BeforeEach
    void setUp() {
        var dynamodbProps = new ZylosDynamodbProperties(TABLE, "test-endpoint");
        var factory = new CartOutboxRecordFactory(
                new ZylosCartServiceProperties("zylos-service-cart", "0.0.0"),
                new ZylosCartOutboxProperties(16, 16),
                () -> "corr-123");
        var outboxTable = enhancedClient.table(TABLE, CartTableSchemas.OUTBOX);
        var expiryProps = new ZylosCartExpiryProperties(8, 8);

        this.scanner = new ExpiredCartScanner(enhancedClient, dynamodbProps, expiryProps);
        this.repository = new DynamoCartRepository(client, enhancedClient, factory, dynamodbProps, expiryProps);
        this.testOutboxShards = 16;
        this.pendingIndex = outboxTable.index(CartTableSchemas.GSI2_OUTBOX_PENDING);
    }

    @Test
    void aWrittenCartIsDiscoverableThroughTheExpiryIndex() {
        Cart cart = newValidatedCart();
        repository.save(cart);

        Instant futureNow = Instant.now().plus(Duration.ofDays(200));

        List<CartId> indexed = scanner.scan(futureNow, 10);
        assertThat(indexed).contains(cart.id());
    }

    @Test
    void expiringACartDeletesItAndOutboxesATerminalEvent() throws IOException {
        Cart cart = newValidatedCart();
        repository.save(cart);
        drainOutbox(); // clear the CartLineAdded record

        Cart loaded = repository.findById(cart.id()).orElseThrow();
        loaded.expire();
        assertThat(repository.expire(loaded)).isTrue();

        assertThat(repository.findById(cart.id())).isEmpty();

        List<OutboxRecordItem> outbox = pendingOutbox();
        assertThat(outbox).singleElement().satisfies(item -> {
            assertThat(item.eventType()).isEqualTo("CartExpired");
            assertThat(item.terminal()).isTrue(); // drives the snapshot-topic tombstone
        });

        CartEvent decoded = CartEvent.fromByteBuffer(outbox.getFirst().payload().asByteBuffer());
        assertThat(decoded.getPayload().getVersion()).isEqualTo(loaded.version()); // bumped, wins under LWW
    }

    @Test
    void aConcurrentModificationCancelsTheExpiryEntirely() {
        Cart cart = newValidatedCart();
        repository.save(cart);

        Cart reaperView = repository.findById(cart.id()).orElseThrow(); // baseVersion = 1
        Cart customerView = repository.findById(cart.id()).orElseThrow();

        customerView.addLine(Sku.of("SKU-2"), Quantity.of(1), usd(500), "seller-1", LineOrigin.VALIDATED);
        repository.save(customerView); // store now at version 2

        reaperView.expire();
        assertThat(repository.expire(reaperView)).isFalse(); // guard held

        assertThat(repository.findById(cart.id())).isPresent(); // the cart survived
        assertThat(pendingOutbox()).noneMatch(item -> "CartExpired".equals(item.eventType()));
    }

    public List<OutboxRecordItem> pendingOutbox() {
        List<OutboxRecordItem> allPending = new ArrayList<>();

        for (int shard = 0; shard < testOutboxShards; shard++) {
            QueryConditional byShard = QueryConditional.keyEqualTo(
                    Key.builder().partitionValue("PENDING#" + shard).build());

            pendingIndex.query(r -> r.queryConditional(byShard)).stream()
                    .flatMap(page -> page.items().stream())
                    .forEach(allPending::add);
        }
        return allPending;
    }

    private void drainOutbox() {
        var outboxTable = enhancedClient.table(TABLE, CartTableSchemas.OUTBOX);
        pendingOutbox().forEach(outboxTable::deleteItem);
    }
}
