package app.zylos.cart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.zylos.cart.domain.event.CartConverted;
import app.zylos.cart.domain.event.CartLineAdded;
import app.zylos.cart.domain.event.CartLineQuantityChanged;
import app.zylos.cart.domain.event.CartLineRemoved;
import app.zylos.cart.domain.exception.*;
import app.zylos.cart.domain.model.CartOwner.CustomerOwner;
import app.zylos.cart.domain.vo.*;

class CartTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final CartOwner OWNER = new CustomerOwner("customer-123");

    private static Cart newCart() {
        return Cart.start(CartId.newId(), OWNER);
    }

    private static PriceSnapshot usd(long minor) {
        return new PriceSnapshot(Money.ofMinor(minor, USD), 1L);
    }

    private static void addValidated(Cart cart, String sku, int qty, long minor) {
        cart.addLine(Sku.of(sku), Quantity.of(qty), usd(minor), "seller-1", LineOrigin.VALIDATED);
    }

    @Test
    void startCreatesEmptyActiveCartAtVersionZeroWithNoEvents() {
        Cart cart = newCart();
        assertThat(cart.status()).isEqualTo(CartStatus.ACTIVE);
        assertThat(cart.version()).isZero();
        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.currency()).isNull();
        assertThat(cart.pullDomainEvents()).isEmpty();
    }

    @Test
    void firstValidatedAddBumpsToVersionOneSetsCurrencyAndEmitsEvent() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 2, 1999);

        assertThat(cart.version()).isEqualTo(1L);
        assertThat(cart.currency()).isEqualTo(USD);
        assertThat(cart.lines()).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo(Sku.of("SKU-1"));
            assertThat(l.quantity()).isEqualTo(Quantity.of(2));
            assertThat(l.origin()).isEqualTo(LineOrigin.VALIDATED);
            assertThat(l.priceSnapshot()).isEqualTo(usd(1999));
        });
        assertThat(cart.pullDomainEvents())
                .singleElement()
                .isInstanceOfSatisfying(
                        CartLineAdded.class, e -> assertThat(e.version()).isEqualTo(1L));
    }

    @Test
    void reAddingSameSkuIncrementsQuantityNotLineCount() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 2, 1999);
        addValidated(cart, "SKU-1", 3, 1999);

        assertThat(cart.lines())
                .singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualTo(Quantity.of(5)));
        assertThat(cart.version()).isEqualTo(2L);
    }

    @Test
    void reAddingCapsQuantityAtMaximum() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 900, 1999);
        addValidated(cart, "SKU-1", 900, 1999);

        assertThat(cart.lines())
                .singleElement()
                .satisfies(l -> assertThat(l.quantity().value()).isEqualTo(Quantity.MAX));
    }

    @Test
    void secondLineInADifferentCurrencyIsRejected() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 1, 1999);

        assertThatThrownBy(() -> cart.addLine(
                        Sku.of("SKU-2"),
                        Quantity.of(1),
                        new PriceSnapshot(Money.ofMinor(500, EUR), 1L),
                        "seller-1",
                        LineOrigin.VALIDATED))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void addingBeyondTheLineLimitIsRejected() {
        Cart cart = newCart();
        for (int i = 0; i < 100; i++) {
            addValidated(cart, "SKU-" + i, 1, 100);
        }
        assertThatThrownBy(() -> addValidated(cart, "SKU-OVERFLOW", 1, 100))
                .isInstanceOf(CartLimitExceededException.class);
    }

    @Test
    void degradedAcceptAddsPendingLineWithoutTouchingCurrency() {
        Cart cart = newCart();
        cart.addLine(Sku.of("SKU-1"), Quantity.of(1), null, null, LineOrigin.VALIDATION_PENDING);

        assertThat(cart.currency()).isNull();
        assertThat(cart.lines()).singleElement().satisfies(l -> {
            assertThat(l.origin()).isEqualTo(LineOrigin.VALIDATION_PENDING);
            assertThat(l.priceSnapshot()).isNull();
            assertThat(l.sellerId()).isNull();
        });
    }

    @Test
    void validatedReAddDoesNotDowngradeButPendingReAddPreservesExistingPrice() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 1, 1999);
        cart.addLine(Sku.of("SKU-1"), Quantity.of(1), null, null, LineOrigin.VALIDATION_PENDING);

        assertThat(cart.lines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualTo(Quantity.of(2));
            assertThat(l.origin()).isEqualTo(LineOrigin.VALIDATED);
            assertThat(l.priceSnapshot()).isEqualTo(usd(1999));
        });
    }

    @Test
    void validatedAddWithoutSnapshotOrSellerIsRejected() {
        Cart cart = newCart();
        assertThatThrownBy(() -> cart.addLine(Sku.of("SKU-1"), Quantity.of(1), null, "seller-1", LineOrigin.VALIDATED))
                .isInstanceOf(CartDomainException.class);
        assertThatThrownBy(() -> cart.addLine(Sku.of("SKU-1"), Quantity.of(1), usd(100), null, LineOrigin.VALIDATED))
                .isInstanceOf(CartDomainException.class);
    }

    @Test
    void changeLineQuantitySetsAbsoluteValueAndEmitsEvent() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 2, 1999);
        cart.pullDomainEvents();

        cart.changeLineQuantity(Sku.of("SKU-1"), Quantity.of(7));

        assertThat(cart.lines())
                .singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualTo(Quantity.of(7)));
        assertThat(cart.pullDomainEvents()).singleElement().isInstanceOf(CartLineQuantityChanged.class);
    }

    @Test
    void changingAnUnknownLineIsRejected() {
        Cart cart = newCart();
        assertThatThrownBy(() -> cart.changeLineQuantity(Sku.of("NOPE"), Quantity.of(1)))
                .isInstanceOf(CartLineNotFoundException.class);
    }

    @Test
    void removingTheLastLineResetsCurrency() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 1, 1999);
        cart.removeLine(Sku.of("SKU-1"));

        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.currency()).isNull();
        assertThat(cart.pullDomainEvents()).last().isInstanceOf(CartLineRemoved.class);
    }

    @Test
    void removingAnUnknownLineIsRejected() {
        Cart cart = newCart();
        assertThatThrownBy(() -> cart.removeLine(Sku.of("NOPE"))).isInstanceOf(CartLineNotFoundException.class);
    }

    @Test
    void clearEmptiesTheCartAndIsANoOpWhenAlreadyEmpty() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 1, 1999);
        addValidated(cart, "SKU-2", 1, 2999);
        long versionBeforeClear = cart.version();

        cart.clear();
        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.currency()).isNull();
        assertThat(cart.version()).isEqualTo(versionBeforeClear + 1);

        long versionAfterClear = cart.version();
        cart.pullDomainEvents();
        cart.clear(); // no-op
        assertThat(cart.version()).isEqualTo(versionAfterClear);
        assertThat(cart.pullDomainEvents()).isEmpty();
    }

    @Test
    void markConvertedTransitionsToTerminalAndBindsTheOrder() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 1, 1999);
        cart.pullDomainEvents();

        cart.markConverted("order-42");

        assertThat(cart.status()).isEqualTo(CartStatus.CONVERTED);
        assertThat(cart.convertedOrderId()).isEqualTo("order-42");
        assertThat(cart.pullDomainEvents())
                .singleElement()
                .isInstanceOfSatisfying(
                        CartConverted.class, e -> assertThat(e.orderId()).isEqualTo("order-42"));
    }

    @Test
    void convertingAnEmptyCartIsRejected() {
        Cart cart = newCart();
        assertThatThrownBy(() -> cart.markConverted("order-1")).isInstanceOf(CartDomainException.class);
    }

    @Test
    void everyMutationIsRejectedOnceConverted() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 1, 1999);
        cart.markConverted("order-1");

        assertThatThrownBy(() -> addValidated(cart, "SKU-2", 1, 100)).isInstanceOf(CartClosedException.class);
        assertThatThrownBy(() -> cart.changeLineQuantity(Sku.of("SKU-1"), Quantity.of(2)))
                .isInstanceOf(CartClosedException.class);
        assertThatThrownBy(() -> cart.removeLine(Sku.of("SKU-1"))).isInstanceOf(CartClosedException.class);
        assertThatThrownBy(cart::clear).isInstanceOf(CartClosedException.class);
        assertThatThrownBy(() -> cart.markConverted("order-2")).isInstanceOf(CartClosedException.class);
    }

    @Test
    void versionIncreasesMonotonicallyAcrossCommands() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 1, 1999); // 1
        addValidated(cart, "SKU-2", 1, 2999); // 2
        cart.changeLineQuantity(Sku.of("SKU-1"), Quantity.of(3)); // 3
        cart.removeLine(Sku.of("SKU-2")); // 4
        assertThat(cart.version()).isEqualTo(4L);
    }

    @Test
    void reconstituteRoundTripsStateWithoutRecordingEvents() {
        Cart cart = newCart();
        addValidated(cart, "SKU-1", 2, 1999);
        cart.pullDomainEvents();

        Cart rehydrated = Cart.reconstitute(
                cart.id(),
                cart.owner(),
                cart.status(),
                cart.currency(),
                cart.lines(),
                cart.convertedOrderId(),
                cart.mergedIntoCartId(),
                cart.version());

        assertThat(rehydrated.version()).isEqualTo(cart.version());
        assertThat(rehydrated.lines()).isEqualTo(cart.lines());
        assertThat(rehydrated.currency()).isEqualTo(USD);
        assertThat(rehydrated.pullDomainEvents()).isEmpty();
    }

    @Test
    void reconstituteRejectsVersionBelowOne() {
        Cart cart = newCart();
        assertThatThrownBy(
                        () -> Cart.reconstitute(cart.id(), OWNER, CartStatus.ACTIVE, null, List.of(), null, null, 0L))
                .isInstanceOf(CartDomainException.class);
    }
}
