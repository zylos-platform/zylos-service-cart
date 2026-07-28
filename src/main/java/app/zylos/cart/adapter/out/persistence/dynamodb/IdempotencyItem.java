package app.zylos.cart.adapter.out.persistence.dynamodb;

import org.jspecify.annotations.Nullable;

import software.amazon.awssdk.core.SdkBytes;

/**
 * Single-table item for one idempotency reservation. Keys: PK=SK=IDEM#&lt;key&gt;.
 */
public record IdempotencyItem(
        String pk,
        String sk,
        String entityType,
        String fingerprint,
        String state,
        @Nullable Integer responseStatus,
        @Nullable String responseContentType,
        @Nullable SdkBytes responseBody,
        long expiresAt) {

    static final String IN_FLIGHT = "IN_FLIGHT";
    static final String COMPLETED = "COMPLETED";

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String pk;
        private String sk;
        private String entityType = "Idempotency";
        private String fingerprint;
        private String state;
        private @Nullable Integer responseStatus;
        private @Nullable String responseContentType;
        private @Nullable SdkBytes responseBody;
        private long expiresAt;

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

        public Builder fingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
            return this;
        }

        public Builder state(String state) {
            this.state = state;
            return this;
        }

        public Builder responseStatus(@Nullable Integer responseStatus) {
            this.responseStatus = responseStatus;
            return this;
        }

        public Builder responseContentType(@Nullable String responseContentType) {
            this.responseContentType = responseContentType;
            return this;
        }

        public Builder responseBody(@Nullable SdkBytes responseBody) {
            this.responseBody = responseBody;
            return this;
        }

        public Builder expiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public IdempotencyItem build() {
            return new IdempotencyItem(
                    pk,
                    sk,
                    entityType,
                    fingerprint,
                    state,
                    responseStatus,
                    responseContentType,
                    responseBody,
                    expiresAt);
        }
    }
}
