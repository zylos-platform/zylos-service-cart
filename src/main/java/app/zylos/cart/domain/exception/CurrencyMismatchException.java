package app.zylos.cart.domain.exception;

import java.io.Serial;

/**
 * Raised when a priced line would introduce a second currency into a cart. A cart holds a single
 * currency, fixed by its first priced line.
 */
public final class CurrencyMismatchException extends CartDomainException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(String message) {
        super(message);
    }
}
