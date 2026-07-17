package app.zylos.cart.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;

import org.junit.jupiter.api.Test;

import app.zylos.cart.domain.exception.CartDomainException;
import app.zylos.cart.domain.vo.*;

class CartLineTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final PriceSnapshot SNAPSHOT = new PriceSnapshot(Money.ofMinor(1999, USD), 3L);

    @Test
    void reconstituteAValidatedLineCarriesPriceAndSeller() {
        CartLine line =
                CartLine.reconstitute(Sku.of("SKU-1"), Quantity.of(2), SNAPSHOT, "seller-1", LineOrigin.VALIDATED);
        assertThat(line.priceSnapshot()).isEqualTo(SNAPSHOT);
        assertThat(line.sellerId()).isEqualTo("seller-1");
        assertThat(line.origin()).isEqualTo(LineOrigin.VALIDATED);
    }

    @Test
    void reconstituteAPendingLineNeedsNoPrice() {
        CartLine line =
                CartLine.reconstitute(Sku.of("SKU-1"), Quantity.of(1), null, null, LineOrigin.VALIDATION_PENDING);
        assertThat(line.priceSnapshot()).isNull();
        assertThat(line.sellerId()).isNull();
    }

    @Test
    void aValidatedLineWithoutPriceOrSellerIsRejected() {
        assertThatThrownBy(() ->
                        CartLine.reconstitute(Sku.of("SKU-1"), Quantity.of(1), null, "seller-1", LineOrigin.VALIDATED))
                .isInstanceOf(CartDomainException.class);
        assertThatThrownBy(() ->
                        CartLine.reconstitute(Sku.of("SKU-1"), Quantity.of(1), SNAPSHOT, "  ", LineOrigin.VALIDATED))
                .isInstanceOf(CartDomainException.class);
    }

    @Test
    void lineIdentityIsItsSku() {
        CartLine a = CartLine.reconstitute(Sku.of("SKU-1"), Quantity.of(1), null, null, LineOrigin.VALIDATION_PENDING);
        CartLine b = CartLine.reconstitute(Sku.of("SKU-1"), Quantity.of(9), null, null, LineOrigin.VALIDATION_PENDING);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
