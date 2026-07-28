package app.zylos.cart.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Currency;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.zylos.cart.adapter.out.security.OpaCartAuthorization;
import app.zylos.cart.application.command.AddLineToCartCommand;
import app.zylos.cart.application.exception.CartContentionException;
import app.zylos.cart.application.exception.SkuNotFoundException;
import app.zylos.cart.application.exception.SkuNotPurchasableException;
import app.zylos.cart.application.port.out.CartRepository;
import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.application.port.out.CatalogLookupPort;
import app.zylos.cart.application.port.out.CatalogSnapshot;
import app.zylos.cart.application.port.out.CurrentOwnerProvider;
import app.zylos.cart.application.port.out.OptimisticConcurrencyException;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.vo.*;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CartCommandServiceTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Sku SKU = Sku.of("SKU-1");
    private static final CartOwner OWNER = new CartOwner.CustomerOwner("customer-1");

    private CartRepository carts;
    private CatalogLookupPort catalog;
    private SimpleMeterRegistry registry;
    private CartCommandService service;

    @BeforeEach
    void setUp() {
        carts = mock(CartRepository.class);
        catalog = mock(CatalogLookupPort.class);
        CurrentOwnerProvider owner = () -> OWNER;
        registry = new SimpleMeterRegistry();

        Retry retry = Retry.of(
                "cart-optimistic",
                RetryConfig.custom()
                        .maxAttempts(4)
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(Duration.ofMillis(1L), 2.0, 0.5))
                        .retryExceptions(OptimisticConcurrencyException.class)
                        .failAfterMaxAttempts(true)
                        .build());

        service = new CartCommandService(carts, catalog, owner, mock(OpaCartAuthorization.class), retry, registry);
        when(carts.findActiveByOwner(OWNER)).thenReturn(Optional.empty());
    }

    private static CatalogLookup found() {
        return new CatalogLookup.Found(new CatalogSnapshot(SKU, Money.ofMinor(1999, USD), "seller-1", 7L));
    }

    private AddLineToCartCommand addOne() {
        return new AddLineToCartCommand(SKU, Quantity.of(1));
    }

    private Cart savedCart() {
        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(carts).save(captor.capture());
        return captor.getValue();
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void aFoundSkuIsAddedAsAValidatedLineWithItsPriceSnapshot() {
        when(catalog.lookup(SKU)).thenReturn(found());

        service.addLine(addOne());

        assertThat(savedCart().lines()).singleElement().satisfies(line -> {
            assertThat(line.origin()).isEqualTo(LineOrigin.VALIDATED);
            assertThat(line.sellerId()).isEqualTo("seller-1");
            assertThat(line.priceSnapshot()).isNotNull();
            assertThat(line.priceSnapshot().unitPrice().minorUnits()).isEqualTo(1999L);
            assertThat(line.priceSnapshot().catalogVersion()).isEqualTo(7L);
        });
        assertThat(counter("zylos.cart.degraded_accept")).isZero();
    }

    @Test
    void anUnknownSkuIsRejectedAndNothingIsPersisted() {
        when(catalog.lookup(SKU)).thenReturn(new CatalogLookup.NotFound(SKU));

        assertThatThrownBy(() -> service.addLine(addOne())).isInstanceOf(SkuNotFoundException.class);

        verify(carts, never()).save(any());
    }

    @Test
    void anUnpurchasableSkuIsRejectedAndNothingIsPersisted() {
        when(catalog.lookup(SKU)).thenReturn(new CatalogLookup.NotPurchasable(SKU));

        assertThatThrownBy(() -> service.addLine(addOne())).isInstanceOf(SkuNotPurchasableException.class);

        verify(carts, never()).save(any());
    }

    @Test
    void anUnavailableCatalogDegradesToAPendingLineInsteadOfFailing() {
        when(catalog.lookup(SKU)).thenReturn(new CatalogLookup.Unavailable(SKU));

        service.addLine(addOne()); // must not throw — the cart never blocks a conversion

        assertThat(savedCart().lines()).singleElement().satisfies(line -> {
            assertThat(line.origin()).isEqualTo(LineOrigin.VALIDATION_PENDING);
            assertThat(line.priceSnapshot()).isNull();
            assertThat(line.sellerId()).isNull();
        });
        assertThat(counter("zylos.cart.degraded_accept")).isEqualTo(1.0);
    }

    @Test
    void catalogIsConsultedOnceEvenWhenThePersistenceAttemptIsRetried() {
        when(catalog.lookup(SKU)).thenReturn(found());
        doThrow(new OptimisticConcurrencyException("conflict", new RuntimeException()))
                .doNothing()
                .when(carts)
                .save(any());

        service.addLine(addOne());

        verify(catalog, times(1)).lookup(SKU); // resolved outside the retry loop
        verify(carts, times(2)).save(any());
        assertThat(counter("zylos.cart.optimistic_retry")).isEqualTo(1.0);
    }

    @Test
    void aLostRaceIsReloadedAndReapplied() {
        when(catalog.lookup(SKU)).thenReturn(found());
        doThrow(new OptimisticConcurrencyException("conflict", new RuntimeException()))
                .doNothing()
                .when(carts)
                .save(any());

        service.addLine(addOne());

        // Reloaded on the second attempt rather than reusing the stale aggregate.
        verify(carts, times(2)).findActiveByOwner(OWNER);
    }

    @Test
    void persistentContentionSurfacesAsAConflictAfterTheAttemptBudget() {
        when(catalog.lookup(SKU)).thenReturn(found());
        doThrow(new OptimisticConcurrencyException("conflict", new RuntimeException()))
                .when(carts)
                .save(any());

        assertThatThrownBy(() -> service.addLine(addOne())).isInstanceOf(CartContentionException.class);

        verify(carts, times(4)).save(any());
        assertThat(counter("zylos.cart.optimistic_retry")).isEqualTo(3.0);
    }

    @Test
    void anExistingCartIsMutatedRatherThanReplaced() {
        Cart existing = Cart.start(CartId.newId(), OWNER);
        existing.addLine(
                Sku.of("SKU-OTHER"),
                Quantity.of(1),
                new app.zylos.cart.domain.vo.PriceSnapshot(Money.ofMinor(500, USD), 1L),
                "seller-2",
                LineOrigin.VALIDATED);

        when(carts.findActiveByOwner(OWNER)).thenReturn(Optional.of(existing));
        when(catalog.lookup(SKU)).thenReturn(found());

        assertThat(service.addLine(addOne())).isEqualTo(existing.id());
        assertThat(savedCart().lines()).hasSize(2);
    }
}
