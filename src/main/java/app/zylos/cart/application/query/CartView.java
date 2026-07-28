package app.zylos.cart.application.query;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A cart as presented to a client.
 *
 * <p>{@code subtotal} is advisory: it is an item subtotal only — no tax, shipping, or promotions — and
 * is re-quoted authoritatively at Checkout. When {@code subtotalComplete} is false at least one line
 * could not be priced and is excluded from the sum; clients must surface that rather than presenting
 * the figure as a total, since a silently under-reported subtotal is worse than none.
 */
public record CartView(
        String cartId,
        String status,
        @Nullable String currency,
        List<CartLineView> lines,
        @Nullable MoneyView subtotal,
        boolean subtotalComplete,
        long version) {

    public static CartView empty() {
        return new CartView(null, "ACTIVE", null, List.of(), null, true, 0L);
    }
}
