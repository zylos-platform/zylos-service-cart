package app.zylos.cart.application.exception;

import java.io.Serial;

/**
 * An identical request holding this key is still executing. Maps to HTTP 409 so the client retries.
 */
public class IdempotencyInFlightException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyInFlightException(String key) {
        super("A request with this Idempotency-Key is currently in progress: " + key);
    }
}
