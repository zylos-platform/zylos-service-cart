package app.zylos.cart.adapter.out.reaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import app.zylos.cart.adapter.out.persistence.dynamodb.CartItem;
import app.zylos.cart.adapter.out.persistence.dynamodb.CartItemMapper;
import app.zylos.cart.adapter.out.persistence.dynamodb.CartLineItem;
import app.zylos.cart.application.port.out.CartRepository;
import app.zylos.cart.config.ZylosCartReaperProperties;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.vo.CartId;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import software.amazon.awssdk.core.exception.SdkClientException;

class CartReaperTest {

    private ExpiredCartScanner scanner;
    private CartRepository carts;
    private SimpleMeterRegistry registry;
    private CartReaper reaper;

    private static CartItem expiredItem(String status, long version) {
        String cartId = CartId.newId().value().toString();
        String pk = "CART#" + cartId;
        Long expiresAt = Instant.now().minusSeconds(10).getEpochSecond();
        return CartItem.builder()
                .pk(pk)
                .sk(pk)
                .cartId(cartId)
                .ownerType("CUSTOMER")
                .ownerId("customer-1")
                .status(status)
                .currency("USD")
                .lines(List.of(CartLineItem.builder()
                        .sku("SKU-1")
                        .quantity(1)
                        .priceMinorUnits(1999L)
                        .priceCurrency("USD")
                        .catalogVersion(1L)
                        .sellerId("seller-1")
                        .origin("VALIDATED")
                        .build()))
                .version(version)
                .createdAt(Instant.now().minusSeconds(100))
                .updatedAt(Instant.now().minusSeconds(100))
                .gsi1pk("EXP#1")
                .gsi1sk(String.valueOf(expiresAt))
                .expiresAt(expiresAt)
                .build();
    }

    private static Cart cartWithId(String expectedId) {
        return argThat(cart -> cart != null && cart.id().value().toString().equals(expectedId));
    }

    @BeforeEach
    void setUp() {
        scanner = mock(ExpiredCartScanner.class);
        carts = mock(CartRepository.class);
        registry = new SimpleMeterRegistry();
        reaper = new CartReaper(
                scanner,
                carts,
                new ZylosCartReaperProperties(
                        200,
                        new ZylosCartReaperProperties.RateLimit(50, Duration.ofSeconds(10)),
                        new ZylosCartReaperProperties.Retry(3, Duration.ofMillis(500))),
                registry);
    }

    private double counter(String name) {
        return registry.get(name).counter().count();
    }

    private CartId mockCartInDatabase(String status, long version) {
        CartItem item = expiredItem(status, version);
        Cart cart = CartItemMapper.toDomain(item);

        when(carts.findById(cart.id())).thenReturn(Optional.of(cart));
        return cart.id();
    }

    @Test
    void expiresEachCandidateWithABumpedVersionAndAnExpiryEvent() {
        CartId cartId = mockCartInDatabase("ACTIVE", 5L);
        when(scanner.scan(any(), anyInt())).thenReturn(List.of(cartId));
        when(carts.expire(any())).thenReturn(true);

        assertThat(reaper.sweep(Instant.now())).isEqualTo(1);

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(carts).expire(captor.capture());
        Cart expiredCart = captor.getValue();
        assertThat(expiredCart.version()).isEqualTo(6L); // bumped so the tombstone wins under LWW
        assertThat(expiredCart.baseVersion()).isEqualTo(5L); // guard is the version the sweep observed
        assertThat(counter("zylos.cart.reaper.expired")).isEqualTo(1.0);
    }

    @Test
    void leavesACartAloneWhenItWasModifiedAfterTheSweepSelectedIt() {
        CartId cartId = mockCartInDatabase("ACTIVE", 5L);
        when(scanner.scan(any(), anyInt())).thenReturn(List.of(cartId));
        when(carts.expire(any())).thenReturn(false); // version guard failed on save

        assertThat(reaper.sweep(Instant.now())).isZero();

        assertThat(counter("zylos.cart.reaper.skipped")).isEqualTo(1.0);
        assertThat(counter("zylos.cart.reaper.expired")).isZero();
    }

    @Test
    void skipsCartIfAlreadyDeletedBeforeHydration() {
        CartId cartId = CartId.newId();
        when(scanner.scan(any(), anyInt())).thenReturn(List.of(cartId));

        // Simulates the race condition where the cart was deleted by the user
        // after the GSI scan, but before findById was called.
        when(carts.findById(cartId)).thenReturn(Optional.empty());

        assertThat(reaper.sweep(Instant.now())).isZero();

        assertThat(counter("zylos.cart.reaper.skipped")).isEqualTo(1.0);
        verify(carts, never()).expire(any());
    }

    @Test
    void expiresConvertedCartsToo() {
        CartId cartId = mockCartInDatabase("CONVERTED", 9L);
        when(scanner.scan(any(), anyInt())).thenReturn(List.of(cartId));
        when(carts.expire(any())).thenReturn(true);

        // A converted cart still occupies storage and still needs a tombstone to leave the topic.
        assertThat(reaper.sweep(Instant.now())).isEqualTo(1);
    }

    @Test
    void aFailedExpiryLeavesTheCartForALaterSweepWithoutAbortingTheBatch() {
        CartId id1 = mockCartInDatabase("ACTIVE", 1L);
        CartId id2 = mockCartInDatabase("ACTIVE", 2L);

        when(scanner.scan(any(), anyInt())).thenReturn(List.of(id1, id2));

        when(carts.expire(cartWithId(id1.value().toString()))).thenThrow(SdkClientException.create("dynamo blip"));
        when(carts.expire(cartWithId(id2.value().toString()))).thenReturn(true);

        assertThat(reaper.sweep(Instant.now())).isEqualTo(1); // Only Cart 2 succeeds

        assertThat(counter("zylos.cart.reaper.failures")).isEqualTo(1.0);
    }

    @Test
    void anEmptySweepDoesNothing() {
        when(scanner.scan(any(), anyInt())).thenReturn(List.of());

        assertThat(reaper.sweep(Instant.now())).isZero();

        verify(carts, times(0)).expire(any());
    }
}
