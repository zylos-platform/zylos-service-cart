package app.zylos.cart.adapter.out.relay;

import java.io.Serial;

/**
 * This instance lost its producer epoch for a shard: another drainer claimed it and the broker fenced
 * us. The in-flight transaction is already void. The relay abandons the shard for this cycle.
 */
public class RelayFencedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RelayFencedException(String message, Throwable cause) {
        super(message, cause);
    }
}
