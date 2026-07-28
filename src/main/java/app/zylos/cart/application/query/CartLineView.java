package app.zylos.cart.application.query;

import org.jspecify.annotations.Nullable;

/**
 * One cart line as presented to a client.
 *
 * <p>{@code unitPrice} is the <em>current</em> catalog price where it could be resolved, not the
 * snapshot captured when the line was added — that is what the customer will actually be quoted. It
 * falls back to the stored snapshot when Catalog cannot be reached, and is null when neither exists.
 */
public record CartLineView(
        String sku,
        int quantity,
        @Nullable MoneyView unitPrice,
        @Nullable MoneyView lineTotal,
        @Nullable String sellerId,
        Availability availability,
        boolean priceProvisional) {

    public enum Availability {
        /** Catalog confirmed the SKU is purchasable. */
        AVAILABLE,
        /** Catalog confirmed the SKU is gone or not purchasable; Checkout will reject it. */
        UNAVAILABLE,
        /** Catalog could not be reached; the line will be resolved authoritatively at Checkout. */
        UNKNOWN
    }
}
