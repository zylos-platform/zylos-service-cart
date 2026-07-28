package app.zylos.cart.adapter.in.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Add or increment a SKU on the caller's cart.
 */
public record AddLineRequest(
        @NotBlank String sku, @Min(1) @Max(999) int quantity) {}
