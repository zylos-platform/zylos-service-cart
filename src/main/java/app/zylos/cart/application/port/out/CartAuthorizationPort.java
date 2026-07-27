package app.zylos.cart.application.port.out;

import app.zylos.cart.domain.model.CartOwner;

/**
 * Outbound port for command-time authorization delegated to the central policy engine.
 */
public interface CartAuthorizationPort {

    void requireAllowed(CartOwner subject, String action);
}
