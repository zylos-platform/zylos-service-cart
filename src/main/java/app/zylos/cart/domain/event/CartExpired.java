package app.zylos.cart.domain.event;

import app.zylos.cart.domain.vo.CartId;

/**
 * The cart's lifetime elapsed and it was reaped. Terminal, and the last record in a cart's stream: the
 * relay publishes it to the events topic and a tombstone to the compacted snapshot topic, so compaction
 * can reclaim the cart.
 */
public record CartExpired(CartId cartId, long version) implements DomainEvent {}
