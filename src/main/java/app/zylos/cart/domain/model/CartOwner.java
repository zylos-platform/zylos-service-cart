package app.zylos.cart.domain.model;

import java.time.Duration;
import java.util.Objects;

import app.zylos.cart.domain.exception.CartDomainException;

/**
 * The principal that owns a cart. A closed set of two shapes — an authenticated customer or an
 * anonymous guest — mirroring the dual-issuer JWT subject model so authorization treats both
 * uniformly. Ownership is captured at cart creation and never changes.
 */
public sealed interface CartOwner permits CartOwner.CustomerOwner, CartOwner.GuestOwner {

    /**
     * The raw subject identifier, as it appears in the JWT {@code sub} claim's value.
     */
    String subjectId();

    /**
     * The time-to-live for the cart, after which it will be automatically removed.
     */
    Duration timeToLive();

    record CustomerOwner(String customerId) implements CartOwner {
        private static final Duration TTL = Duration.ofDays(180);

        public CustomerOwner {
            Objects.requireNonNull(customerId, "customerId must not be null");
            if (customerId.isBlank()) {
                throw new CartDomainException("customerId must not be blank");
            }
        }

        @Override
        public String subjectId() {
            return customerId;
        }

        @Override
        public Duration timeToLive() {
            return TTL;
        }
    }

    record GuestOwner(String guestId) implements CartOwner {
        private static final Duration TTL = Duration.ofDays(30);

        public GuestOwner {
            Objects.requireNonNull(guestId, "guestId must not be null");
            if (guestId.isBlank()) {
                throw new CartDomainException("guestId must not be blank");
            }
        }

        @Override
        public String subjectId() {
            return guestId;
        }

        @Override
        public Duration timeToLive() {
            return TTL;
        }
    }
}
