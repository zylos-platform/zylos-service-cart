package app.zylos.cart.application.query;

/** Monetary amount for presentation: integer minor units plus its ISO-4217 code. */
public record MoneyView(long minorUnits, String currency) {}
