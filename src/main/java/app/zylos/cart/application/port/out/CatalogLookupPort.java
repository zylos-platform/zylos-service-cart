package app.zylos.cart.application.port.out;

import app.zylos.cart.domain.vo.Sku;

/**
 * Outbound port for resolving a SKU against the Catalog bounded context. Implementations must never
 * throw for a missing or unpurchasable SKU — those are {@link CatalogLookup} results — and must return
 * {@link CatalogLookup.Unavailable} rather than propagating transport failures, so the caller can
 * apply degraded-accept.
 */
public interface CatalogLookupPort {

    CatalogLookup lookup(Sku sku);
}
