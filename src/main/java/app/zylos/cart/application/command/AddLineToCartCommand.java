package app.zylos.cart.application.command;

import app.zylos.cart.domain.vo.Quantity;
import app.zylos.cart.domain.vo.Sku;

/** Add (or increment) a SKU on the caller's active cart. */
public record AddLineToCartCommand(Sku sku, Quantity quantity) {}
