package app.zylos.cart.adapter.out.relay;

import app.zylos.cart.config.ZylosCartProperties;

public class CartTestFixtures {

    public static ZylosCartProperties defaultCartProperties() {
        return builder().build();
    }

    public static CartPropertiesBuilder builder() {
        return new CartPropertiesBuilder();
    }

    public static class CartPropertiesBuilder {
        private String eventsTopic = "cart.cart.events.v1";
        private String snapshotTopic = "cart.cart.snapshot.v1";
        private long pollIntervalMs = 1000L;
        private int batchSize = 100;
        private long leaseTtlSeconds = 30L;
        private long sendTimeoutSeconds = 10L;
        private long publishedRetentionHours = 24L;
        private int leaseRenewEveryRecords = 20;
        private long leaseSafetyMarginMs = 5000L;

        public CartPropertiesBuilder eventsTopic(String eventsTopic) {
            this.eventsTopic = eventsTopic;
            return this;
        }

        public CartPropertiesBuilder snapshotTopic(String snapshotTopic) {
            this.snapshotTopic = snapshotTopic;
            return this;
        }

        public CartPropertiesBuilder pollIntervalMs(int pollIntervalMs) {
            this.pollIntervalMs = pollIntervalMs;
            return this;
        }

        public CartPropertiesBuilder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public CartPropertiesBuilder leaseTtlSeconds(long leaseTtlSeconds) {
            this.leaseTtlSeconds = leaseTtlSeconds;
            return this;
        }

        public CartPropertiesBuilder sendTimeoutSeconds(long sendTimeoutSeconds) {
            this.sendTimeoutSeconds = sendTimeoutSeconds;
            return this;
        }

        public CartPropertiesBuilder publishedRetentionHours(long publishedRetentionHours) {
            this.publishedRetentionHours = publishedRetentionHours;
            return this;
        }

        public CartPropertiesBuilder leaseRenewEveryRecords(int leaseRenewEveryRecords) {
            this.leaseRenewEveryRecords = leaseRenewEveryRecords;
            return this;
        }

        public CartPropertiesBuilder leaseSafetyMarginMs(long leaseSafetyMarginMs) {
            this.leaseSafetyMarginMs = leaseSafetyMarginMs;
            return this;
        }

        public ZylosCartProperties build() {
            ZylosCartProperties.Relay relay = new ZylosCartProperties.Relay(
                    pollIntervalMs,
                    batchSize,
                    leaseTtlSeconds,
                    leaseSafetyMarginMs,
                    leaseRenewEveryRecords,
                    sendTimeoutSeconds,
                    publishedRetentionHours);
            return new ZylosCartProperties(eventsTopic, snapshotTopic, relay);
        }
    }
}
