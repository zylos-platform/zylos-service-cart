package app.zylos.cart.adapter.out.relay;

import java.time.Duration;
import java.time.Instant;

/**
 * A time-boxed claim on a shard. The deadline is advisory: it stops a pod doing work it is likely to
 * lose, but it cannot provide mutual exclusion in an asynchronous system (a GC pause can outlive any
 * deadline check). Actual exclusion comes from the Kafka producer epoch — see {@link ShardProducerRegistry}.
 */
public record RelayLease(int shard, String owner, Instant expiresAt) {

    /**
     * True when the lease is within {@code margin} of expiry (or already past it).
     */
    public boolean isExpiringWithin(Duration margin, Instant now) {
        return !now.isBefore(expiresAt.minus(margin));
    }
}
