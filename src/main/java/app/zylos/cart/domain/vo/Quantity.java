package app.zylos.cart.domain.vo;

import app.zylos.cart.domain.exception.CartDomainException;

/**
 * A positive per-line quantity, bounded to a sane maximum. Zero is not a valid quantity: a request
 * to set a line to zero is a removal, resolved before a {@code Quantity} is constructed.
 */
public record Quantity(int value) {

    public static final int MIN = 1;
    public static final int MAX = 999;

    public Quantity {
        if (value < MIN || value > MAX) {
            throw new CartDomainException("quantity must be between %d and %d, was %d".formatted(MIN, MAX, value));
        }
    }

    public static Quantity of(int value) {
        return new Quantity(value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
