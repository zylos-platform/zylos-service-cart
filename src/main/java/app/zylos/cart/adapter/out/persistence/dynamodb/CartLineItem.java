package app.zylos.cart.adapter.out.persistence.dynamodb;

import org.jspecify.annotations.Nullable;

/**
 * Persistence projection of a cart line (nested document within {@link CartItem}). Immutable.
 */
public record CartLineItem(
        String sku,
        int quantity,
        @Nullable Long priceMinorUnits,
        @Nullable String priceCurrency,
        @Nullable Long catalogVersion,
        @Nullable String sellerId,
        String origin) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String sku;
        private int quantity;
        private @Nullable Long priceMinorUnits;
        private @Nullable String priceCurrency;
        private @Nullable Long catalogVersion;
        private @Nullable String sellerId;
        private String origin;

        public Builder sku(String sku) {
            this.sku = sku;
            return this;
        }

        public Builder quantity(int quantity) {
            this.quantity = quantity;
            return this;
        }

        public Builder priceMinorUnits(@Nullable Long priceMinorUnits) {
            this.priceMinorUnits = priceMinorUnits;
            return this;
        }

        public Builder priceCurrency(@Nullable String priceCurrency) {
            this.priceCurrency = priceCurrency;
            return this;
        }

        public Builder catalogVersion(@Nullable Long catalogVersion) {
            this.catalogVersion = catalogVersion;
            return this;
        }

        public Builder sellerId(@Nullable String sellerId) {
            this.sellerId = sellerId;
            return this;
        }

        public Builder origin(String origin) {
            this.origin = origin;
            return this;
        }

        public CartLineItem build() {
            return new CartLineItem(sku, quantity, priceMinorUnits, priceCurrency, catalogVersion, sellerId, origin);
        }
    }
}
