package app.zylos.cart.adapter.out.persistence.dynamodb;

public record ActiveCartPointerItem(String pk, String sk, String cartId) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String pk;
        private String sk;
        private String cartId;

        public Builder pk(String pk) {
            this.pk = pk;
            return this;
        }

        public Builder sk(String sk) {
            this.sk = sk;
            return this;
        }

        public Builder cartId(String cartId) {
            this.cartId = cartId;
            return this;
        }

        public ActiveCartPointerItem build() {
            return new ActiveCartPointerItem(pk, sk, cartId);
        }
    }
}
