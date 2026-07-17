package app.zylos.cart.domain.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;

import org.junit.jupiter.api.Test;

import app.zylos.cart.domain.exception.CartDomainException;

class PriceSnapshotTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void exposesCurrencyFromUnitPrice() {
        assertThat(new PriceSnapshot(Money.ofMinor(1999, USD), 5L).currency()).isEqualTo(USD);
    }

    @Test
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> new PriceSnapshot(Money.ofMinor(-1, USD), 1L)).isInstanceOf(CartDomainException.class);
    }

    @Test
    void rejectsCatalogVersionBelowOne() {
        assertThatThrownBy(() -> new PriceSnapshot(Money.ofMinor(1, USD), 0L)).isInstanceOf(CartDomainException.class);
    }
}
