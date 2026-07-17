package app.zylos.cart.domain.event;

import app.zylos.cart.domain.vo.CartId;

/** All lines were removed in a single command. */
public record CartCleared(CartId cartId, long version) implements DomainEvent {}
