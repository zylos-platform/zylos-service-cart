package app.zylos.cart.domain.vo;

import java.util.Currency;
import java.util.Objects;

import app.zylos.cart.domain.exception.CartDomainException;

/**
 * An advisory snapshot of a SKU's price taken from Catalog at add/refresh time. Non-authoritative by
 * contract: it exists to render a subtotal hint, and is re-quoted at Checkout. {@code catalogVersion}
 * lets Checkout detect staleness against the current Catalog aggregate.
 */
public record PriceSnapshot(Money unitPrice, long catalogVersion) {

    public PriceSnapshot {
        Objects.requireNonNull(unitPrice, "unitPrice must not be null");

        if (unitPrice.isNegative()) {
            throw new CartDomainException("unitPrice must not be negative: " + unitPrice);
        }

        if (catalogVersion < 1L) {
            throw new CartDomainException("catalogVersion must be >= 1, was " + catalogVersion);
        }
    }

    public Currency currency() {
        return unitPrice.currency();
    }
}
