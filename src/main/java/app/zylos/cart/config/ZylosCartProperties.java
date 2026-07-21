package app.zylos.cart.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.cart")
public record ZylosCartProperties(
        @NotBlank String eventsTopic,

        @NotBlank String snapshotTopic,

        @NotNull @Valid Relay relay) {
    public record Relay(
            @Positive @DefaultValue("1000") long pollIntervalMs,
            @Positive @DefaultValue("100") int batchSize,
            @Positive @DefaultValue("30") long leaseTtlSeconds,
            @Positive @DefaultValue("5000") long leaseSafetyMarginMs,
            @Positive @DefaultValue("20") long leaseRenewEveryRecords,
            @Positive @DefaultValue("10") long sendTimeoutSeconds,
            @Positive @DefaultValue("24") long publishedRetentionHours) {}
}
