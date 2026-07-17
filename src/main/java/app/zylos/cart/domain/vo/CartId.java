package app.zylos.cart.domain.vo;

import java.util.Objects;
import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;

import app.zylos.cart.domain.exception.CartDomainException;

/**
 * Identity of a {@code Cart} aggregate. New identities are time-ordered UUIDv7, so the
 * partition key sorts chronologically and indexes well.
 */
public record CartId(UUID value) {

    public CartId {
        Objects.requireNonNull(value, "value must not be null");
    }

    public static CartId newId() {
        return new CartId(UuidCreator.getTimeOrderedEpoch());
    }

    public static CartId of(UUID value) {
        return new CartId(value);
    }

    public static CartId of(String value) {
        Objects.requireNonNull(value, "value must not be null");

        try {
            return new CartId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new CartDomainException("Invalid CartId: " + value, e);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
