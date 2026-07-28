package app.zylos.cart.application.port.in;

import app.zylos.cart.application.query.CartView;

/** Inbound port for cart reads. */
public interface CartQueryPort {

    CartView getCart();
}
