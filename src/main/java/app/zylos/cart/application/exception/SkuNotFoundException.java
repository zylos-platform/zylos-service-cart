package app.zylos.cart.application.exception;

import java.io.Serial;

import app.zylos.cart.domain.vo.Sku;

/**
 * Catalog answered authoritatively that the SKU does not exist. Distinct from Catalog being
 * unavailable, which degrades instead of rejecting. Adapters map this to HTTP 404.
 */
public class SkuNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SkuNotFoundException(Sku sku) {
        super("No such SKU: " + sku);
    }
}
