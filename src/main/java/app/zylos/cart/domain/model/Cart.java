package app.zylos.cart.domain.model;

import java.util.*;

import org.jspecify.annotations.Nullable;

import app.zylos.cart.domain.event.*;
import app.zylos.cart.domain.exception.*;
import app.zylos.cart.domain.vo.*;

/**
 * Cart aggregate root.
 *
 * <p>Consistency boundary: every invariant holds within a single instance and is enforced on each
 * mutating command. A successful command increments the monotonic {@code version} by exactly one and
 * records one thin {@link DomainEvent} carrying that version; the version is the optimistic-
 * concurrency guard and the last-writer-wins key for downstream projections.
 *
 * <p>The cart is a durable statement of intent — <em>not</em> a pricing or inventory authority. Line
 * prices are advisory snapshots; a stale or absent snapshot is by design, re-quoted authoritatively
 * at Checkout. The aggregate never checks stock, applies promotions, computes tax, or verifies SKU
 * existence: existence is verified on the add path by the application layer (which hands the
 * aggregate a validated snapshot or, under degraded-accept, a {@code VALIDATION_PENDING} line).
 *
 * <p>Commands are deltas, never blind full-state replacements.
 */
public final class Cart {

    private static final int MAX_LINES = 100;

    private final CartId id;
    private final CartOwner owner;
    private final List<CartLine> lines;
    private final List<DomainEvent> domainEvents = new ArrayList<>();
    private CartStatus status;
    private @Nullable Currency currency;
    private @Nullable String convertedOrderId;
    private @Nullable CartId mergedIntoCartId;
    private long version;
    private final long baseVersion;

    private Cart(
            CartId id,
            CartOwner owner,
            CartStatus status,
            @Nullable Currency currency,
            List<CartLine> lines,
            @Nullable String convertedOrderId,
            @Nullable CartId mergedIntoCartId,
            long version) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(lines, "lines must not be null");
        this.lines = new ArrayList<>(lines);
        this.currency = currency;
        this.convertedOrderId = convertedOrderId;
        this.mergedIntoCartId = mergedIntoCartId;
        this.version = version;
        this.baseVersion = version;
    }

    /**
     * Starts a new, empty {@code ACTIVE} cart at version 0. No event is recorded: an empty cart has
     * no business meaning and is never persisted or published until its first line is added.
     */
    public static Cart start(CartId id, CartOwner owner) {
        return new Cart(id, owner, CartStatus.ACTIVE, null, new ArrayList<>(), null, null, 0L);
    }

    /**
     * Rehydrates a cart from persisted state without recording events. For repository adapters.
     */
    public static Cart reconstitute(
            CartId id,
            CartOwner owner,
            CartStatus status,
            @Nullable Currency currency,
            List<CartLine> lines,
            @Nullable String convertedOrderId,
            @Nullable CartId mergedIntoCartId,
            long version) {

        if (version < 1L) {
            throw new CartDomainException("version must be >= 1, was " + version);
        }
        return new Cart(id, owner, status, currency, lines, convertedOrderId, mergedIntoCartId, version);
    }

    private static String requireSeller(@Nullable String sellerId, Sku sku) {
        if (sellerId == null || sellerId.isBlank()) {
            throw new CartDomainException("A VALIDATED add requires a seller: " + sku);
        }
        return sellerId;
    }

    /**
     * Adds {@code quantity} of {@code sku}, or increases an existing line by that amount (capped at
     * {@link Quantity#MAX}). A {@code VALIDATED} add fixes/enforces the cart currency and refreshes
     * the line's price; a {@code VALIDATION_PENDING} add never downgrades an already-validated line.
     */
    public void addLine(
            Sku sku,
            Quantity quantity,
            @Nullable PriceSnapshot priceSnapshot,
            @Nullable String sellerId,
            LineOrigin origin) {
        ensureActive();
        Objects.requireNonNull(sku, "sku must not be null");
        Objects.requireNonNull(quantity, "quantity must not be null");
        Objects.requireNonNull(origin, "origin must not be null");

        if (origin == LineOrigin.VALIDATED && priceSnapshot == null) {
            throw new CartDomainException("A VALIDATED add requires a price snapshot: " + sku);
        }

        CartLine existing = findLine(sku).orElse(null);
        if (existing != null) {
            int summed = existing.quantity().value() + quantity.value();
            existing.setQuantity(Quantity.of(Math.min(summed, Quantity.MAX)));

            if (origin == LineOrigin.VALIDATED) {
                applyCurrencyOf(priceSnapshot);
                existing.applyValidation(priceSnapshot, requireSeller(sellerId, sku));
            }
        } else {
            if (lines.size() >= MAX_LINES) {
                throw new CartLimitExceededException("Cart may hold at most %d lines".formatted(MAX_LINES));
            }

            if (origin == LineOrigin.VALIDATED) {
                applyCurrencyOf(priceSnapshot);
            }
            lines.add(CartLine.create(sku, quantity, priceSnapshot, sellerId, origin));
        }

        bumpVersion();
        recordEvent(new CartLineAdded(id, sku, version));
    }

    /**
     * Sets the absolute quantity of an existing line.
     */
    public void changeLineQuantity(Sku sku, Quantity newQuantity) {
        ensureActive();
        Objects.requireNonNull(newQuantity, "newQuantity must not be null");
        CartLine line = requireLine(sku);
        line.setQuantity(newQuantity);
        bumpVersion();
        recordEvent(new CartLineQuantityChanged(id, sku, version));
    }

    /**
     * Removes a line. When the last line is removed, the cart's currency is reset.
     */
    public void removeLine(Sku sku) {
        ensureActive();
        CartLine line = requireLine(sku);
        lines.remove(line);

        if (lines.isEmpty()) {
            currency = null;
        }
        bumpVersion();
        recordEvent(new CartLineRemoved(id, sku, version));
    }

    /**
     * Removes all lines. Idempotent: a no-op on an already-empty cart.
     */
    public void clear() {
        ensureActive();
        if (lines.isEmpty()) {
            return;
        }

        lines.clear();
        currency = null;
        bumpVersion();
        recordEvent(new CartCleared(id, version));
    }

    /**
     * Terminal transition to {@code CONVERTED}, binding the cart to the order it became.
     */
    public void markConverted(String orderId) {
        ensureActive();
        Objects.requireNonNull(orderId, "orderId must not be null");

        if (orderId.isBlank()) {
            throw new CartDomainException("orderId must not be blank");
        }

        if (lines.isEmpty()) {
            throw new CartDomainException("Cannot convert an empty cart: " + id);
        }
        status = CartStatus.CONVERTED;
        convertedOrderId = orderId;
        bumpVersion();
        recordEvent(new CartConverted(id, orderId, version));
    }

    /**
     * Returns and clears the events recorded since the last pull.
     */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> pulled = List.copyOf(domainEvents);
        domainEvents.clear();
        return pulled;
    }

    public CartId id() {
        return id;
    }

    public CartOwner owner() {
        return owner;
    }

    public CartStatus status() {
        return status;
    }

    public @Nullable Currency currency() {
        return currency;
    }

    public List<CartLine> lines() {
        return List.copyOf(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public long version() {
        return version;
    }

    /**
     * The version at which this aggregate was loaded (or 0 for a never-persisted cart). The
     * persistence adapter conditions its write on this value for optimistic concurrency; commands do
     * not change it. An aggregate instance is saved at most once per unit of work.
     */
    public long baseVersion() {
        return baseVersion;
    }

    public @Nullable String convertedOrderId() {
        return convertedOrderId;
    }

    public @Nullable CartId mergedIntoCartId() {
        return mergedIntoCartId;
    }

    private Optional<CartLine> findLine(Sku sku) {
        Objects.requireNonNull(sku, "sku must not be null");
        return lines.stream().filter(l -> l.sku().equals(sku)).findFirst();
    }

    private CartLine requireLine(Sku sku) {
        return findLine(sku).orElseThrow(() -> new CartLineNotFoundException("Line not found: " + sku));
    }

    private void applyCurrencyOf(PriceSnapshot priceSnapshot) {
        Currency lineCurrency = priceSnapshot.currency();

        if (currency == null) {
            currency = lineCurrency;
        } else if (!currency.equals(lineCurrency)) {
            throw new CurrencyMismatchException("Cart currency is %s; cannot add a %s line"
                    .formatted(currency.getCurrencyCode(), lineCurrency.getCurrencyCode()));
        }
    }

    private void ensureActive() {
        if (status != CartStatus.ACTIVE) {
            throw new CartClosedException("Cart %s is %s and cannot be modified".formatted(id, status));
        }
    }

    private void recordEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    private void bumpVersion() {
        version = Math.incrementExact(version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cart other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Cart[id=%s, owner=%s, status=%s, version=%d, lines=%d]"
                .formatted(id, owner, status, version, lines.size());
    }
}
