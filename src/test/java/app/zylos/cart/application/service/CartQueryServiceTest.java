package app.zylos.cart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Currency;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zylos.cart.application.port.out.*;
import app.zylos.cart.application.query.CartLineView.Availability;
import app.zylos.cart.application.query.CartView;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.vo.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CartQueryServiceTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final CartOwner OWNER = new CartOwner.CustomerOwner("customer-1");
    private static final Sku SKU_A = Sku.of("SKU-A");
    private static final Sku SKU_B = Sku.of("SKU-B");

    private CartRepository carts;
    private CatalogLookupPort catalog;
    private CartQueryService service;

    private static CatalogLookup found(Sku sku, long minor) {
        return new CatalogLookup.Found(new CatalogSnapshot(sku, Money.ofMinor(minor, USD), "seller-1", 1L));
    }

    @BeforeEach
    void setUp() {
        carts = mock(CartRepository.class);
        catalog = mock(CatalogLookupPort.class);
        service = new CartQueryService(
                carts, catalog, () -> OWNER, mock(CartAuthorizationPort.class), new SimpleMeterRegistry());
    }

    private Cart cartWith(Sku sku, int quantity, @Nullable Long snapshotMinor) {
        Cart cart = Cart.start(CartId.newId(), OWNER);
        PriceSnapshot snapshot =
                snapshotMinor == null ? null : new PriceSnapshot(Money.ofMinor(snapshotMinor, USD), 1L);
        cart.addLine(
                sku,
                Quantity.of(quantity),
                snapshot,
                snapshot == null ? null : "seller-1",
                snapshot == null ? LineOrigin.VALIDATION_PENDING : LineOrigin.VALIDATED);
        when(carts.findActiveByOwner(OWNER)).thenReturn(Optional.of(cart));
        return cart;
    }

    @Test
    void anAbsentCartReadsAsEmptyWithoutCreatingOne() {
        when(carts.findActiveByOwner(OWNER)).thenReturn(Optional.empty());

        CartView view = service.getCart();

        assertThat(view.lines()).isEmpty();
        assertThat(view.subtotalComplete()).isTrue();
        verify(carts, times(0)).save(any());
    }

    @Test
    void everyLineIsResolvedInASingleBatchedCall() {
        Cart cart = cartWith(SKU_A, 1, 1000L);
        cart.addLine(
                SKU_B,
                Quantity.of(1),
                new PriceSnapshot(Money.ofMinor(2000, USD), 1L),
                "seller-1",
                LineOrigin.VALIDATED);
        when(catalog.lookupAll(any())).thenReturn(Map.of(SKU_A, found(SKU_A, 1000), SKU_B, found(SKU_B, 2000)));

        service.getCart();

        verify(catalog, times(1)).lookupAll(any()); // one round trip, never one call per line
    }

    @Test
    void theViewShowsTheCurrentCatalogPriceRatherThanTheStoredSnapshot() {
        cartWith(SKU_A, 2, 1000L);
        when(catalog.lookupAll(any())).thenReturn(Map.of(SKU_A, found(SKU_A, 1500))); // price rose since adding

        CartView view = service.getCart();

        assertThat(view.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice().minorUnits()).isEqualTo(1500L);
            assertThat(line.lineTotal().minorUnits()).isEqualTo(3000L);
            assertThat(line.availability()).isEqualTo(Availability.AVAILABLE);
            assertThat(line.priceProvisional()).isFalse();
        });
        assertThat(view.subtotal().minorUnits()).isEqualTo(3000L);
        assertThat(view.subtotalComplete()).isTrue();
    }

    @Test
    void aPendingLineIsPricedForDisplayButNotPersisted() {
        cartWith(SKU_A, 1, null); // VALIDATION_PENDING, no stored price
        when(catalog.lookupAll(any())).thenReturn(Map.of(SKU_A, found(SKU_A, 999)));

        CartView view = service.getCart();

        assertThat(view.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice().minorUnits()).isEqualTo(999L);
            assertThat(line.availability()).isEqualTo(Availability.AVAILABLE);
        });
        verify(carts, times(0)).save(any()); // reads never write
    }

    @Test
    void aDelistedLineIsMarkedUnavailableAndExcludedFromAnIncompleteSubtotal() {
        cartWith(SKU_A, 1, 1000L);
        when(catalog.lookupAll(any())).thenReturn(Map.of(SKU_A, new CatalogLookup.NotPurchasable(SKU_A)));

        CartView view = service.getCart();

        assertThat(view.lines()).singleElement().satisfies(line -> {
            assertThat(line.availability()).isEqualTo(Availability.UNAVAILABLE);
            assertThat(line.lineTotal()).isNull();
        });
        assertThat(view.subtotalComplete()).isFalse(); // never silently omit a line from the total
    }

    @Test
    void withCatalogUnavailableTheCartStillReadsFromStoredSnapshots() {
        cartWith(SKU_A, 3, 1000L);
        when(catalog.lookupAll(any())).thenReturn(Map.of(SKU_A, new CatalogLookup.Unavailable(SKU_A)));

        CartView view = service.getCart();

        assertThat(view.lines()).singleElement().satisfies(line -> {
            assertThat(line.availability()).isEqualTo(Availability.UNKNOWN); // not UNAVAILABLE
            assertThat(line.unitPrice().minorUnits()).isEqualTo(1000L); // stored snapshot
            assertThat(line.priceProvisional()).isTrue();
        });
        assertThat(view.subtotal().minorUnits()).isEqualTo(3000L);
    }

    @Test
    void anUnpricedLineDuringAnOutageFlagsTheSubtotalIncomplete() {
        cartWith(SKU_A, 1, null); // pending, no snapshot to fall back to
        when(catalog.lookupAll(any())).thenReturn(Map.of(SKU_A, new CatalogLookup.Unavailable(SKU_A)));

        CartView view = service.getCart();

        assertThat(view.lines()).singleElement().satisfies(line -> {
            assertThat(line.unitPrice()).isNull();
            assertThat(line.availability()).isEqualTo(Availability.UNKNOWN);
        });
        assertThat(view.subtotalComplete()).isFalse();
    }
}
