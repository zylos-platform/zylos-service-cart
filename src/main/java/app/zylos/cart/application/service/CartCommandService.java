package app.zylos.cart.application.service;

import java.util.Optional;
import java.util.function.UnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.zylos.cart.application.command.AddLineToCartCommand;
import app.zylos.cart.application.command.ChangeLineQuantityCommand;
import app.zylos.cart.application.exception.CartContentionException;
import app.zylos.cart.application.exception.SkuNotFoundException;
import app.zylos.cart.application.exception.SkuNotPurchasableException;
import app.zylos.cart.application.port.in.CartCommandPort;
import app.zylos.cart.application.port.out.*;
import app.zylos.cart.domain.model.Cart;
import app.zylos.cart.domain.model.CartOwner;
import app.zylos.cart.domain.vo.*;

import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Application service implementing the cart write use cases.
 *
 * <p>A SKU is resolved against Catalog before anything is persisted. An
 * authoritative negative ({@code NotFound} / {@code NotPurchasable}) rejects the command; Catalog being
 * <em>unavailable</em> does not. In that case the line is written {@code VALIDATION_PENDING} with no
 * price, to be validated lazily on read and authoritatively at Checkout. The cart must never block a
 * conversion: failing add-to-cart because Catalog is degraded would turn a Catalog outage into a
 * revenue outage.
 *
 * <p><b>Concurrency.</b> Each command runs in a bounded reload-and-re-apply loop against
 * {@link OptimisticConcurrencyException}. This is correct precisely because cart commands are deltas
 * rather than blind full-state writes — re-applying "add 1 of X" to a cart that changed underneath is
 * still what the user asked for.
 *
 * <p><b>Ordering.</b> The Catalog lookup happens once, <em>outside</em> the retry loop: the snapshot
 * does not change between attempts, and re-querying per retry would multiply Catalog load exactly when
 * the system is already contended. It also keeps all network IO off the persistence path.
 */
@Service
public class CartCommandService implements CartCommandPort {

    private static final Logger log = LoggerFactory.getLogger(CartCommandService.class);

    private final CartRepository carts;
    private final CatalogLookupPort catalog;
    private final CurrentOwnerProvider currentOwner;
    private final Retry cartOptimisticRetry;
    private final Counter degradedAccepts;
    private final Counter optimisticRetries;

    public CartCommandService(
            CartRepository carts,
            CatalogLookupPort catalog,
            CurrentOwnerProvider currentOwner,
            Retry cartOptimisticRetry,
            MeterRegistry registry) {
        this.carts = carts;
        this.catalog = catalog;
        this.currentOwner = currentOwner;
        this.cartOptimisticRetry = cartOptimisticRetry;

        this.degradedAccepts = Counter.builder("zylos.cart.degraded_accept")
                .description("Lines accepted VALIDATION_PENDING because Catalog was unavailable")
                .register(registry);
        this.optimisticRetries = Counter.builder("zylos.cart.optimistic_retry")
                .description("Cart command attempts that lost the optimistic-concurrency race and were re-applied")
                .register(registry);

        this.cartOptimisticRetry.getEventPublisher().onRetry(_ -> optimisticRetries.increment());
    }

    @Override
    public CartId addLine(AddLineToCartCommand command) {
        // Resolved once, before any persistence attempt.
        LineSpec spec = resolve(command.sku());

        return mutate(
                cart -> {
                    switch (spec) {
                        case LineSpec.Validated(
                                Sku sku,
                                PriceSnapshot priceSnapshot,
                                String sellerId,
                                LineOrigin origin) ->
                            cart.addLine(sku, command.quantity(), priceSnapshot, sellerId, origin);
                        case LineSpec.Pending(Sku sku, LineOrigin origin) ->
                            cart.addLine(sku, command.quantity(), null, null, origin);
                    }
                    return cart;
                },
                true);
    }

    @Override
    public CartId changeLineQuantity(ChangeLineQuantityCommand command) {
        Quantity quantity = command.quantity();
        return mutate(
                cart -> {
                    cart.changeLineQuantity(command.sku(), quantity);
                    return cart;
                },
                false);
    }

    @Override
    public CartId removeLine(Sku sku) {
        return mutate(
                cart -> {
                    cart.removeLine(sku);
                    return cart;
                },
                false);
    }

    @Override
    public CartId clearCart() {
        return mutate(
                cart -> {
                    cart.clear();
                    return cart;
                },
                false);
    }

    /**
     * Maps a catalog answer onto the line to write, rejecting only authoritative negatives.
     */
    private LineSpec resolve(Sku sku) {
        return switch (catalog.lookup(sku)) {
            case CatalogLookup.Found(CatalogSnapshot snapshot) ->
                new LineSpec.Validated(
                        sku,
                        new PriceSnapshot(snapshot.unitPrice(), snapshot.catalogVersion()),
                        snapshot.sellerId(),
                        LineOrigin.VALIDATED);

            case CatalogLookup.NotFound _ -> throw new SkuNotFoundException(sku);
            case CatalogLookup.NotPurchasable _ -> throw new SkuNotPurchasableException(sku);
            case CatalogLookup.Unavailable _ -> {
                // Catalog did not answer. Unknown != nonexistent: accept, mark pending, let Checkout decide.
                degradedAccepts.increment();
                log.debug("Catalog unavailable for {}; accepting line as VALIDATION_PENDING", sku);
                yield new LineSpec.Pending(sku, LineOrigin.VALIDATION_PENDING);
            }
        };
    }

    /**
     * Runs {@code mutation} against the caller's active cart under a bounded reload-and-re-apply loop.
     * When {@code createIfAbsent}, a missing cart is started lazily (an empty cart has no business
     * meaning and is never persisted until its first line).
     */
    private CartId mutate(UnaryOperator<Cart> mutation, boolean createIfAbsent) {
        CartOwner owner = currentOwner.currentOwner();

        try {
            return cartOptimisticRetry.executeSupplier(() -> {
                Optional<Cart> existing = carts.findActiveByOwner(owner);
                Cart cart = existing.orElseGet(() -> {
                    if (!createIfAbsent) {
                        throw new IllegalStateException("No active cart for the current owner");
                    }
                    return Cart.start(CartId.newId(), owner);
                });

                carts.save(mutation.apply(cart));
                return cart.id();
            });
        } catch (OptimisticConcurrencyException e) {
            throw new CartContentionException(
                    "Cart command lost the optimistic-concurrency race after max attempts", e);
        }
    }

    /**
     * What to write for a line, once Catalog has been consulted.
     */
    private sealed interface LineSpec {
        Sku sku();

        LineOrigin origin();

        record Validated(Sku sku, PriceSnapshot priceSnapshot, String sellerId, LineOrigin origin)
                implements LineSpec {}

        record Pending(Sku sku, LineOrigin origin) implements LineSpec {}
    }
}
