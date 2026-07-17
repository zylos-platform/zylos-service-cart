package app.zylos.cart.domain.vo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

import app.zylos.cart.domain.exception.CartDomainException;

/**
 * Monetary amount stored as an integral number of minor units (e.g. cents) in a single ISO-4217
 * currency.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency must not be null");
    }

    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    /**
     * Builds a {@code Money} from a major-unit decimal (e.g. {@code 12.34}). The amount must be
     * expressible exactly in the currency's minor units; excess precision is rejected rather than
     * silently rounded.
     */
    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        int fractionDigits = Math.max(currency.getDefaultFractionDigits(), 0);

        try {
            BigDecimal scaled = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
            return new Money(scaled.movePointRight(fractionDigits).longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new CartDomainException(
                    "Amount %s cannot be represented exactly in %s minor units"
                            .formatted(amount, currency.getCurrencyCode()),
                    e);
        }
    }

    private static long addExact(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new CartDomainException("Monetary overflow on addition", e);
        }
    }

    private static long multiplyExact(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new CartDomainException("Monetary overflow on multiplication", e);
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(addExact(minorUnits, other.minorUnits), currency);
    }

    public Money multipliedBy(long factor) {
        return new Money(multiplyExact(minorUnits, factor), currency);
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    /**
     * Major-unit representation, e.g. {@code 12.34}.
     */
    public BigDecimal toAmount() {
        return BigDecimal.valueOf(minorUnits).movePointLeft(Math.max(currency.getDefaultFractionDigits(), 0));
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    @Override
    public String toString() {
        return "%s %s".formatted(toAmount().toPlainString(), currency.getCurrencyCode());
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");

        if (!currency.equals(other.currency)) {
            throw new CartDomainException("Currency mismatch: %s vs %s"
                    .formatted(currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }
}
