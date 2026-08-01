package app.zylos.cart.domain.model;

import java.util.Objects;

import app.zylos.cart.domain.exception.CartClosedException;

/**
 * Lifecycle state of a {@code Cart} aggregate and the rules governing transitions.
 *
 * <ul>
 *   <li>{@code ACTIVE} — the normal, mutable state.
 *   <li>{@code CONVERTED} — terminal; the cart became an order.
 *   <li>{@code MERGED} — terminal; a guest cart absorbed into a customer cart.
 * </ul>
 *
 * <p>Abandonment and expiry are deliberately <em>not</em> statuses: they are judgments derived
 * downstream from event timing, not states the aggregate mutates itself into on a timer.
 */
public enum CartStatus {
    ACTIVE,
    CONVERTED,
    MERGED;

    public boolean canTransitionTo(CartStatus target) {
        Objects.requireNonNull(target, "target must not be null");

        return switch (this) {
            case ACTIVE -> target == CONVERTED || target == MERGED;
            case CONVERTED, MERGED -> false;
        };
    }

    public boolean isTerminal() {
        return switch (this) {
            case ACTIVE -> false;
            case CONVERTED, MERGED -> true;
        };
    }

    public void ensureCanTransitionTo(CartStatus target) {
        Objects.requireNonNull(target, "target must not be null");

        if (!canTransitionTo(target)) {
            throw new CartClosedException("Cart cannot transition from %s to %s".formatted(this, target));
        }
    }
}
