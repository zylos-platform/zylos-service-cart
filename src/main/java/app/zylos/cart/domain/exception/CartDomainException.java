package app.zylos.cart.domain.exception;

import java.io.Serial;

/**
 * Base type for all domain-rule violations raised within the Cart bounded context.
 */
public class CartDomainException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CartDomainException(String message) {
        super(message);
    }

    public CartDomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
