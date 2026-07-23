package app.zylos.cart.application.port.out;

import app.zylos.cart.domain.vo.Sku;

/**
 * The outcome of resolving a SKU against Catalog. The four cases are deliberately distinct: only
 * {@link Unavailable} means "unknown" (degrade), while {@link NotFound} and {@link NotPurchasable} are
 * authoritative negatives (reject). Collapsing these into an Optional would let an outage-time miss be
 * treated the same as a nonexistent SKU.
 */
public sealed interface CatalogLookup
        permits CatalogLookup.Found, CatalogLookup.NotFound, CatalogLookup.NotPurchasable, CatalogLookup.Unavailable {

    /** Catalog confirmed the SKU; the snapshot is priced and purchasable. */
    record Found(CatalogSnapshot snapshot) implements CatalogLookup {}

    /** Catalog answered authoritatively: no such SKU. Reject the add. */
    record NotFound(Sku sku) implements CatalogLookup {}

    /** Catalog answered authoritatively: the SKU exists but is not purchasable. Reject the add. */
    record NotPurchasable(Sku sku) implements CatalogLookup {}

    /** Catalog did not answer (timeout, open circuit, saturation). Unknown — degrade, don't reject. */
    record Unavailable(Sku sku) implements CatalogLookup {}
}
