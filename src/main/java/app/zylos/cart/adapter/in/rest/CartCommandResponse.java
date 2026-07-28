package app.zylos.cart.adapter.in.rest;

/**
 * Identifies the cart a command acted on, so the client can address it directly afterwards.
 */
public record CartCommandResponse(String cartId) {}
