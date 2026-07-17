package app.zylos.cart.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import app.zylos.cart.domain.exception.CartDomainException;

class QuantityTest {

    @Test
    void acceptsValuesWithinBounds() {
        assertThat(Quantity.of(1).value()).isEqualTo(1);
        assertThat(Quantity.of(Quantity.MAX).value()).isEqualTo(999);
    }

    @Test
    void rejectsZeroAndNegative() {
        assertThatThrownBy(() -> Quantity.of(0)).isInstanceOf(CartDomainException.class);
        assertThatThrownBy(() -> Quantity.of(-1)).isInstanceOf(CartDomainException.class);
    }

    @Test
    void rejectsAboveMaximum() {
        assertThatThrownBy(() -> Quantity.of(Quantity.MAX + 1)).isInstanceOf(CartDomainException.class);
    }
}
