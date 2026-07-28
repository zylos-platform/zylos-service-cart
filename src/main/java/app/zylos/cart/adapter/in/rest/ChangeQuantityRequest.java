package app.zylos.cart.adapter.in.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Set an existing line to an absolute quantity.
 */
public record ChangeQuantityRequest(@Min(1) @Max(999) int quantity) {}
