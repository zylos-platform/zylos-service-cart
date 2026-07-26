package app.zylos.cart.application.port.out;

import app.zylos.cart.domain.model.CartOwner;

/**
 * Supplies the authenticated principal owning the in-flight request's cart.
 */
@FunctionalInterface
public interface CurrentOwnerProvider {

    CartOwner currentOwner();
}
