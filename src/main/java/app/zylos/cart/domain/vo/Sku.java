package app.zylos.cart.domain.vo;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import app.zylos.cart.domain.exception.CartDomainException;

/**
 * Stock Keeping Unit — the identity of a {@code CartLine} within a cart.
 */
public record Sku(String value) {

    private static final int MAX_LENGTH = 64;
    private static final Pattern PATTERN = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,63}$");

    public Sku {
        Objects.requireNonNull(value, "value must not be null");
        value = value.trim().toUpperCase(Locale.ROOT);

        if (value.isEmpty()) {
            throw new CartDomainException("SKU must not be blank");
        }

        if (value.length() > MAX_LENGTH) {
            throw new CartDomainException("SKU must be at most %d characters: %s".formatted(MAX_LENGTH, value));
        }

        if (!PATTERN.matcher(value).matches()) {
            throw new CartDomainException("SKU has invalid format: " + value);
        }
    }

    public static Sku of(String value) {
        return new Sku(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
