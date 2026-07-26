package app.zylos.cart.application.exception;

import java.io.Serial;

/**
 * A cart command lost the optimistic-concurrency race on every permitted attempt. Rare and
 * observable; adapters map this to HTTP 409 so the client may retry.
 */
public class CartContentionException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CartContentionException(String message, Throwable cause) {
        super(message, cause);
    }
}
