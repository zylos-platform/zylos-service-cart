package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Single-table item for a cart aggregate. Keys: PK=SK=CART#&lt;cartId&gt;. Immutable.
 */
public record CartItem(
        String pk,
        String sk,
        String entityType,
        String cartId,
        String ownerType,
        String ownerId,
        String status,
        @Nullable String currency,
        List<CartLineItem> lines,
        long version,
        Instant createdAt,
        Instant updatedAt,
        @Nullable String convertedOrderId,
        @Nullable String mergedIntoCartId,
        String gsi1pk,
        String gsi1sk,
        @Nullable Long expiresAt) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String pk;
        private String sk;
        private String entityType = "Cart";
        private String cartId;
        private String ownerType;
        private String ownerId;
        private String status;
        private @Nullable String currency;
        private List<CartLineItem> lines = List.of();
        private long version;
        private Instant createdAt;
        private Instant updatedAt;
        private @Nullable String convertedOrderId;
        private @Nullable String mergedIntoCartId;
        private String gsi1pk;
        private String gsi1sk;
        private @Nullable Long expiresAt;

        public Builder pk(String pk) {
            this.pk = pk;
            return this;
        }

        public Builder sk(String sk) {
            this.sk = sk;
            return this;
        }

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder cartId(String cartId) {
            this.cartId = cartId;
            return this;
        }

        public Builder ownerType(String ownerType) {
            this.ownerType = ownerType;
            return this;
        }

        public Builder ownerId(String ownerId) {
            this.ownerId = ownerId;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder currency(@Nullable String currency) {
            this.currency = currency;
            return this;
        }

        public Builder lines(List<CartLineItem> lines) {
            this.lines = lines;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public Builder convertedOrderId(@Nullable String convertedOrderId) {
            this.convertedOrderId = convertedOrderId;
            return this;
        }

        public Builder mergedIntoCartId(@Nullable String mergedIntoCartId) {
            this.mergedIntoCartId = mergedIntoCartId;
            return this;
        }

        public Builder gsi1pk(String gsi1pk) {
            this.gsi1pk = gsi1pk;
            return this;
        }

        public Builder gsi1sk(String gsi1sk) {
            this.gsi1sk = gsi1sk;
            return this;
        }

        public Builder expiresAt(@Nullable Long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public CartItem build() {
            return new CartItem(
                    pk,
                    sk,
                    entityType,
                    cartId,
                    ownerType,
                    ownerId,
                    status,
                    currency,
                    lines,
                    version,
                    createdAt,
                    updatedAt,
                    convertedOrderId,
                    mergedIntoCartId,
                    gsi1pk,
                    gsi1sk,
                    expiresAt);
        }
    }
}
