package app.zylos.cart.application.service;

import java.util.*;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import app.zylos.cart.application.port.in.CartQueryPort;
import app.zylos.cart.application.port.out.*;
import app.zylos.cart.application.query.CartLineView;
import app.zylos.cart.application.query.CartLineView.Availability;
import app.zylos.cart.application.query.CartView;
import app.zylos.cart.application.query.MoneyView;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartLine;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.vo.Money;
import app.zylos.cart.domain.vo.PriceSnapshot;
import app.zylos.cart.domain.vo.Sku;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Read use case for the cart.
 *
 * <p>Every line is re-resolved against Catalog in a single batched call, so the view shows current
 * prices and catches products delisted since they were added — the surprise that would otherwise only
 * appear at Checkout. One round trip, mostly served from cache; this is what the batch RPC exists for.
 *
 * <p><b>Enrichment is never persisted.</b> A read leaves the stored aggregate untouched: GET stays safe
 * and idempotent, cannot race a concurrent command under the optimistic-concurrency model, and does not
 * put write amplification on the hottest endpoint. A {@code VALIDATION_PENDING} line therefore stays
 * pending in storage even when this read could price it; the next write command or Checkout resolves it.
 *
 * <p>If Catalog is unreachable the cart is still returned, priced from stored snapshots where possible
 * and flagged {@code UNKNOWN} — the cart must always be readable, for the same reason it must always be
 * writable.
 */
@Service
public class CartQueryService implements CartQueryPort {

    private final CartRepository carts;
    private final CatalogLookupPort catalog;
    private final CurrentOwnerProvider currentOwner;
    private final CartAuthorizationPort authorization;
    private final Counter unpricedLines;

    public CartQueryService(
            CartRepository carts,
            CatalogLookupPort catalog,
            CurrentOwnerProvider currentOwner,
            CartAuthorizationPort authorization,
            MeterRegistry registry) {
        this.carts = carts;
        this.catalog = catalog;
        this.currentOwner = currentOwner;
        this.authorization = authorization;
        this.unpricedLines = Counter.builder("zylos.cart.read.unpriced_lines")
                .description("Cart lines rendered without a price because neither Catalog nor a snapshot supplied one")
                .register(registry);
    }

    private static CartLineView build(
            CartLine line,
            @Nullable Money unitPrice,
            @Nullable String sellerId,
            Availability availability,
            boolean provisional) {
        MoneyView unit = unitPrice == null
                ? null
                : new MoneyView(unitPrice.minorUnits(), unitPrice.currency().getCurrencyCode());
        MoneyView total = unitPrice == null
                ? null
                : new MoneyView(
                        unitPrice.multipliedBy(line.quantity().value()).minorUnits(),
                        unitPrice.currency().getCurrencyCode());

        return new CartLineView(
                line.sku().value(), line.quantity().value(), unit, total, sellerId, availability, provisional);
    }

    @Override
    public CartView getCart() {
        CartOwner owner = currentOwner.currentOwner();
        authorization.requireAllowed(owner, CartAction.CART_READ);

        Optional<Cart> found = carts.findActiveByOwner(owner);
        if (found.isEmpty()) {
            return CartView.empty(); // an absent cart reads as an empty one; no lazy creation on read
        }
        Cart cart = found.get();

        Set<Sku> skus =
                new LinkedHashSet<>(cart.lines().stream().map(CartLine::sku).toList());
        Map<Sku, CatalogLookup> resolved = catalog.lookupAll(skus);

        List<CartLineView> lines = new ArrayList<>(cart.lines().size());
        long subtotalMinor = 0L;
        boolean subtotalComplete = true;
        String currency = cart.currency() == null ? null : cart.currency().getCurrencyCode();

        for (CartLine line : cart.lines()) {
            CartLineView view = toView(line, resolved.get(line.sku()));
            lines.add(view);

            if (view.lineTotal() == null) {
                subtotalComplete = false;
                unpricedLines.increment();
            } else {
                subtotalMinor += view.lineTotal().minorUnits();
                currency = currency == null ? view.lineTotal().currency() : currency;
            }
        }

        MoneyView subtotal = currency == null ? null : new MoneyView(subtotalMinor, currency);
        return new CartView(
                cart.id().toString(),
                cart.status().name(),
                currency,
                lines,
                subtotal,
                subtotalComplete,
                cart.version());
    }

    private CartLineView toView(CartLine line, @Nullable CatalogLookup lookup) {
        PriceSnapshot stored = line.priceSnapshot();

        return switch (lookup) {
            // Catalog answered: prefer the current price over the captured snapshot — it is what the
            // customer will be quoted, and the snapshot is advisory by contract.
            case CatalogLookup.Found(CatalogSnapshot snapshot) ->
                build(line, snapshot.unitPrice(), snapshot.sellerId(), Availability.AVAILABLE, false);

            case CatalogLookup.NotFound _, CatalogLookup.NotPurchasable _ ->
                build(line, null, line.sellerId(), Availability.UNAVAILABLE, false);

            // Catalog silent: fall back to the snapshot, and say the price is provisional.
            case null, default ->
                build(line, stored == null ? null : stored.unitPrice(), line.sellerId(), Availability.UNKNOWN, true);
        };
    }
}
