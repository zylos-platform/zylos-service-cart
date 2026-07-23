package app.zylos.cart.adapter.out.catalog.grpc;

import java.io.Serial;

/** A transient failure calling Catalog (timeout, unavailable, transport). Trips the circuit breaker. */
public class CatalogUnavailableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
