package app.zylos.cart.application.port.out;

import java.time.Duration;

import org.jspecify.annotations.Nullable;

/**
 * Store for HTTP idempotency reservations, keyed by client-supplied {@code Idempotency-Key}.
 *
 * <p>A reservation is claimed before the command runs and completed with the response afterwards, so a
 * retried request replays the original outcome instead of re-executing. The stored fingerprint binds a
 * key to the exact request that claimed it: reusing a key with a different payload is a client error,
 * not a replay.
 */
public interface IdempotencyStore {

    Reservation reserve(String key, String fingerprint, Duration inFlightTtl);

    /**
     * Stores the response so subsequent requests with this key replay it.
     */
    void complete(String key, String fingerprint, int status, @Nullable String contentType, byte[] body, Duration ttl);

    /**
     * Abandons a reservation so the client may genuinely retry (used when the command did not succeed).
     */
    void release(String key);

    /**
     * Outcome of claiming a key.
     */
    sealed interface Reservation
            permits Reservation.Fresh, Reservation.Replay, Reservation.InFlight, Reservation.Conflict {

        /**
         * The key is ours; execute the command and then {@link #complete}.
         */
        record Fresh() implements Reservation {}

        /**
         * The same request already completed; return the stored response verbatim.
         */
        record Replay(int status, @Nullable String contentType, byte[] body) implements Reservation {}

        /**
         * An identical request is still executing elsewhere; the client should retry shortly.
         */
        record InFlight() implements Reservation {}

        /**
         * The key was used before with a different request body.
         */
        record Conflict() implements Reservation {}
    }
}
