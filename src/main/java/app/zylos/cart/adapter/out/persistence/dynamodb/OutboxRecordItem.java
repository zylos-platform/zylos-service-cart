package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.time.Instant;

import software.amazon.awssdk.core.SdkBytes;

/**
 * Single-table item for one outbox record. Keys: PK=OUTBOX#&lt;shard&gt;, SK=&lt;outboxId&gt;. Immutable.
 */
public record OutboxRecordItem(
        String pk,
        String sk,
        String entityType,
        int shardId,
        String outboxId,
        String eventId,
        String eventType,
        String aggregateId,
        String aggregateType,
        String cartId,
        Instant occurredAt,
        boolean terminal,
        SdkBytes payload) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String pk;
        private String sk;
        private String entityType = "Outbox";
        private int shardId;
        private String outboxId;
        private String eventId;
        private String eventType;
        private String aggregateId;
        private String aggregateType;
        private String cartId;
        private Instant occurredAt;
        private boolean terminal;
        private SdkBytes payload;

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

        public Builder shardId(int shardId) {
            this.shardId = shardId;
            return this;
        }

        public Builder outboxId(String outboxId) {
            this.outboxId = outboxId;
            return this;
        }

        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder aggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder aggregateType(String aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public Builder cartId(String cartId) {
            this.cartId = cartId;
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public Builder terminal(boolean terminal) {
            this.terminal = terminal;
            return this;
        }

        public Builder payload(SdkBytes payload) {
            this.payload = payload;
            return this;
        }

        public OutboxRecordItem build() {
            return new OutboxRecordItem(
                    pk,
                    sk,
                    entityType,
                    shardId,
                    outboxId,
                    eventId,
                    eventType,
                    aggregateId,
                    aggregateType,
                    cartId,
                    occurredAt,
                    terminal,
                    payload);
        }
    }
}
