package app.zylos.cart.domain.vo;

/**
 * Whether a cart line was existence/price-validated against Catalog when it was written, or accepted
 * under degraded-accept for later validation.
 *
 * <ul>
 *   <li>{@code VALIDATED} — Catalog confirmed the SKU; the line carries a price snapshot and seller.
 *   <li>{@code VALIDATION_PENDING} — added while Catalog was unavailable; carries no price. Validated
 *       lazily on the next read and authoritatively at Checkout.
 * </ul>
 */
public enum LineOrigin {
    VALIDATED,
    VALIDATION_PENDING
}
