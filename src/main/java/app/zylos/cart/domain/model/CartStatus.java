package app.zylos.cart.domain.model;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    private static final Map<CartStatus, Set<CartStatus>> ALLOWED_TARGETS = new EnumMap<>(CartStatus.class);

    static {
        ALLOWED_TARGETS.put(ACTIVE, Set.of(CONVERTED, MERGED));
        ALLOWED_TARGETS.put(CONVERTED, Set.of());
        ALLOWED_TARGETS.put(MERGED, Set.of());
    }

    public boolean canTransitionTo(CartStatus target) {
        return ALLOWED_TARGETS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED_TARGETS.getOrDefault(this, Set.of()).isEmpty();
    }

    public void ensureCanTransitionTo(CartStatus target) {
        Objects.requireNonNull(target, "target must not be null");

        if (!canTransitionTo(target)) {
            throw new CartClosedException("Cart cannot transition from %s to %s".formatted(this, target));
        }
    }
}
