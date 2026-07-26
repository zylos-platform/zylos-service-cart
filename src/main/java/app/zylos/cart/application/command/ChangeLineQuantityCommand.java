package app.zylos.cart.application.command;

import app.zylos.cart.domain.vo.Quantity;
import app.zylos.cart.domain.vo.Sku;

/** Set an existing line to an absolute quantity. */
public record ChangeLineQuantityCommand(Sku sku, Quantity quantity) {}
