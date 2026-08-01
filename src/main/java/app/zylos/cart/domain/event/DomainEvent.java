package app.zylos.cart.domain.event;

import app.zylos.cart.domain.vo.CartId;

/**
 * A fact that has happened to the Cart aggregate. Domain events are intentionally thin: they carry
 * the cart identity, the version they produced, and only data intrinsic to the intent (e.g. the
 * affected SKU). The full event-carried state-transfer snapshot for the wire is assembled by the
 * outbox adapter from current aggregate state, keeping the domain free of serialization and envelope
 * concerns.
 */
public sealed interface DomainEvent
        permits CartLineAdded, CartLineQuantityChanged, CartLineRemoved, CartCleared, CartConverted, CartExpired {

    CartId cartId();

    long version();
}
