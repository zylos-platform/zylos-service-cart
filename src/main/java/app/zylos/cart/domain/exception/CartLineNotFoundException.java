package app.zylos.cart.domain.exception;

import java.io.Serial;

/**
 * Raised when a command references a SKU that is not present in the cart.
 */
public final class CartLineNotFoundException extends CartDomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CartLineNotFoundException(String message) {
        super(message);
    }
}
