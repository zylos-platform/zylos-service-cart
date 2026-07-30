package app.zylos.cart.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "zylos.cart.reaper")
public record ZylosCartReaperProperties(
    @DefaultValue("200") @Min(1) int batchSize,
    @DefaultValue @Valid RateLimit rateLimit,
    @DefaultValue @Valid Retry retry
) {

    public ZylosCartReaperProperties {
        if (rateLimit != null) {
            long minimumRequiredSeconds = (long) Math.ceil((double) batchSize / rateLimit.maxWritesPerSecond());

            if (rateLimit.timeout().getSeconds() <= minimumRequiredSeconds) {
                throw new IllegalArgumentException(
                    "Invalid zylos.cart.reaper configuration: rateLimit.timeout (%ss) must be strictly greater than batchSize / rateLimit.maxWritesPerSecond (%ss)."
                        .formatted(rateLimit.timeout().getSeconds(), minimumRequiredSeconds)
                );
            }
        }
    }

    public record RateLimit(
        @DefaultValue("50") @Min(1) int maxWritesPerSecond,
        @DefaultValue("10s") @NotNull Duration timeout
    ) {
    }

    public record Retry(
        @DefaultValue("3") @Min(1) int maxAttempts,
        @DefaultValue("500ms") @NotNull Duration waitDuration
    ) {
    }
}
