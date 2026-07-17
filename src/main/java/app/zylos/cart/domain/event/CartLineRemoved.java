package app.zylos.cart.domain.event;

import app.zylos.cart.domain.vo.CartId;
import app.zylos.cart.domain.vo.Sku;

/** A line was removed from the cart. */
public record CartLineRemoved(CartId cartId, Sku sku, long version) implements DomainEvent {}
