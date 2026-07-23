package app.zylos.cart.adapter.out.catalog.cache;

import java.util.Currency;

import org.jspecify.annotations.Nullable;

import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.application.port.out.CatalogSnapshot;
import app.zylos.cart.domain.vo.Money;
import app.zylos.cart.domain.vo.Sku;

/**
 * The cached form of a catalog answer. Stores primitives (not domain VOs) for stable JSON, and
 * {@code freshUntilEpochMs} for the two-tier TTL. {@link CatalogLookup.Unavailable} is never cached.
 */
public record CatalogCacheEntry(
        String kind,
        @Nullable Long minorUnits,
        @Nullable String currency,
        @Nullable String sellerId,
        @Nullable Long catalogVersion,
        long freshUntilEpochMs) {

    static final String FOUND = "FOUND";
    static final String NOT_FOUND = "NOT_FOUND";
    static final String NOT_PURCHASABLE = "NOT_PURCHASABLE";

    static CatalogCacheEntry of(CatalogLookup lookup, long freshUntilEpochMs) {
        return switch (lookup) {
            case CatalogLookup.Found(CatalogSnapshot s) ->
                new CatalogCacheEntry(
                        FOUND,
                        s.unitPrice().minorUnits(),
                        s.unitPrice().currency().getCurrencyCode(),
                        s.sellerId(),
                        s.catalogVersion(),
                        freshUntilEpochMs);

            case CatalogLookup.NotFound _ ->
                new CatalogCacheEntry(NOT_FOUND, null, null, null, null, freshUntilEpochMs);

            case CatalogLookup.NotPurchasable _ ->
                new CatalogCacheEntry(NOT_PURCHASABLE, null, null, null, null, freshUntilEpochMs);

            case CatalogLookup.Unavailable _ -> throw new IllegalArgumentException("Unavailable is never cached");
        };
    }

    boolean isFresh(long nowEpochMs) {
        return nowEpochMs < freshUntilEpochMs;
    }

    boolean isNegative() {
        return !FOUND.equals(kind);
    }

    CatalogLookup toLookup(Sku sku) {
        return switch (kind) {
            case FOUND ->
                new CatalogLookup.Found(new CatalogSnapshot(
                        sku,
                        Money.ofMinor(minorUnits(), Currency.getInstance(currency())),
                        sellerId(),
                        catalogVersion()));
            case NOT_PURCHASABLE -> new CatalogLookup.NotPurchasable(sku);
            default -> new CatalogLookup.NotFound(sku);
        };
    }
}
