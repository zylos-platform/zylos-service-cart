package app.zylos.cart.domain.model;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import app.zylos.cart.domain.exception.CartDomainException;
import app.zylos.cart.domain.vo.LineOrigin;
import app.zylos.cart.domain.vo.PriceSnapshot;
import app.zylos.cart.domain.vo.Quantity;
import app.zylos.cart.domain.vo.Sku;

/**
 * A single line within a {@code Cart} aggregate.
 *
 * <p>An entity whose identity is its {@link Sku} (immutable). Quantity, price snapshot, seller and
 * origin are mutable, but only through package-private operations invoked by the {@code Cart} root:
 * external callers holding a reference returned from {@code Cart#lines()} cannot mutate it, so every
 * change stays subject to the root's invariants (currency, limits).
 *
 * <p>Correlation invariant: a {@code VALIDATED} line always carries a price snapshot and seller; a
 * {@code VALIDATION_PENDING} line may carry neither.
 */
public final class CartLine {

    private final Sku sku;
    private Quantity quantity;
    private @Nullable PriceSnapshot priceSnapshot;
    private @Nullable String sellerId;
    private LineOrigin origin;

    private CartLine(
            Sku sku,
            Quantity quantity,
            @Nullable PriceSnapshot priceSnapshot,
            @Nullable String sellerId,
            LineOrigin origin) {
        this.sku = Objects.requireNonNull(sku, "sku must not be null");
        this.quantity = Objects.requireNonNull(quantity, "quantity must not be null");
        this.origin = Objects.requireNonNull(origin, "origin must not be null");
        this.priceSnapshot = priceSnapshot;
        this.sellerId = normaliseSeller(sellerId);
        ensureOriginCorrelation();
    }

    /**
     * Fresh line. Root-only: lines are created via the {@code Cart} aggregate.
     */
    static CartLine create(
            Sku sku,
            Quantity quantity,
            @Nullable PriceSnapshot priceSnapshot,
            @Nullable String sellerId,
            LineOrigin origin) {
        return new CartLine(sku, quantity, priceSnapshot, sellerId, origin);
    }

    /**
     * Rehydrates a line from persisted state. For repository adapters.
     */
    public static CartLine reconstitute(
            Sku sku,
            Quantity quantity,
            @Nullable PriceSnapshot priceSnapshot,
            @Nullable String sellerId,
            LineOrigin origin) {
        return new CartLine(sku, quantity, priceSnapshot, sellerId, origin);
    }

    private static @Nullable String normaliseSeller(@Nullable String sellerId) {
        if (sellerId == null) {
            return null;
        }
        String trimmed = sellerId.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void ensureOriginCorrelation() {
        if (origin == LineOrigin.VALIDATED && (priceSnapshot == null || sellerId == null)) {
            throw new CartDomainException("A VALIDATED line requires a price snapshot and a seller: " + sku);
        }
    }

    void setQuantity(Quantity quantity) {
        this.quantity = Objects.requireNonNull(quantity, "quantity must not be null");
    }

    /**
     * Promotes a line to {@code VALIDATED} with a confirmed price and seller.
     */
    void applyValidation(PriceSnapshot priceSnapshot, String sellerId) {
        this.priceSnapshot = Objects.requireNonNull(priceSnapshot, "priceSnapshot must not be null");
        this.sellerId = normaliseSeller(sellerId);
        this.origin = LineOrigin.VALIDATED;
        ensureOriginCorrelation();
    }

    public Sku sku() {
        return sku;
    }

    public Quantity quantity() {
        return quantity;
    }

    public @Nullable PriceSnapshot priceSnapshot() {
        return priceSnapshot;
    }

    public @Nullable String sellerId() {
        return sellerId;
    }

    public LineOrigin origin() {
        return origin;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CartLine other)) {
            return false;
        }
        return sku.equals(other.sku);
    }

    @Override
    public int hashCode() {
        return sku.hashCode();
    }

    @Override
    public String toString() {
        return "CartLine[sku=%s, quantity=%s, origin=%s]".formatted(sku, quantity, origin);
    }
}
