package app.zylos.cart.domain.exception;

import java.io.Serial;

/**
 * Raised when a mutating command targets a cart that is no longer {@code ACTIVE} (it has been
 * converted to an order or merged).
 */
public final class CartClosedException extends CartDomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CartClosedException(String message) {
        super(message);
    }
}
