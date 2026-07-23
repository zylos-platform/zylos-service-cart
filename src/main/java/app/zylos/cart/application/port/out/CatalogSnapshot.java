package app.zylos.cart.application.port.out;

import app.zylos.cart.domain.vo.Money;
import app.zylos.cart.domain.vo.Sku;

/** An advisory, resolved catalog view of a purchasable SKU, ready to price a cart line. */
public record CatalogSnapshot(Sku sku, Money unitPrice, String sellerId, long catalogVersion) {}
