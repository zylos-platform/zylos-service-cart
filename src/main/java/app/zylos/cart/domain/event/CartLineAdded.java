package app.zylos.cart.domain.event;

import app.zylos.cart.domain.vo.CartId;
import app.zylos.cart.domain.vo.Sku;

/**
 * A line was added, or an existing line's quantity was increased by a re-add.
 */
public record CartLineAdded(CartId cartId, Sku sku, long version) implements DomainEvent {}
