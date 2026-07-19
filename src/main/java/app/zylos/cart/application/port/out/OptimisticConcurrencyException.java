package app.zylos.cart.application.port.out;

import java.io.Serial;

/**
 * Signals that a cart write lost an optimistic-concurrency race: the stored version no longer matches
 * the version the aggregate was loaded at. The application handles this by reloading and re-applying
 * the command — which is correct precisely because cart commands are deltas, not blind replacements.
 */
public class OptimisticConcurrencyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public OptimisticConcurrencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
