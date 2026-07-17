package app.zylos.cart.domain.event;

import app.zylos.cart.domain.vo.CartId;

/** The cart was converted into an order by the Checkout saga. Terminal. */
public record CartConverted(CartId cartId, String orderId, long version) implements DomainEvent {}
