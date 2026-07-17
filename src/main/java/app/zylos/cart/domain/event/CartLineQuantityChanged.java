package app.zylos.cart.domain.event;

import app.zylos.cart.domain.vo.CartId;
import app.zylos.cart.domain.vo.Sku;

/** A line's quantity was set to a new absolute value. */
public record CartLineQuantityChanged(CartId cartId, Sku sku, long version) implements DomainEvent {}
