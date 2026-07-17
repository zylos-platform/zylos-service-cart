package app.zylos.cart.domain.exception;

import java.io.Serial;

/**
 * Raised when a command would exceed a structural cart limit (maximum distinct lines).
 */
public final class CartLimitExceededException extends CartDomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CartLimitExceededException(String message) {
        super(message);
    }
}
