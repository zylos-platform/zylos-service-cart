package app.zylos.cart.application.exception;

import java.io.Serial;

import app.zylos.cart.domain.vo.Sku;

/**
 * Catalog answered authoritatively that the SKU exists but cannot be purchased. Maps to HTTP 422.
 */
public class SkuNotPurchasableException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SkuNotPurchasableException(Sku sku) {
        super("SKU is not purchasable: " + sku);
    }
}
