package app.zylos.cart.adapter.out.relay;

import java.io.Serial;

/** A transient failure publishing to Kafka; the relay retains the outbox record and retries. */
public class RelayPublishException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RelayPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
