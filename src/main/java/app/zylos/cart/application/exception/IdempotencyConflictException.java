package app.zylos.cart.application.exception;

import java.io.Serial;

/**
 * The supplied {@code Idempotency-Key} was already used for a different request. Maps to HTTP 422.
 */
public class IdempotencyConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key already used with a different request: " + key);
    }
}
