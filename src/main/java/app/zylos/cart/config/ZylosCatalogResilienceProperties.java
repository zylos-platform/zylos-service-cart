package app.zylos.cart.config;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.catalog.resilience")
public record ZylosCatalogResilienceProperties(
        @Positive int cbFailureRateThreshold,
        @Positive int cbSlidingWindow,
        @Positive int cbWaitOpenSeconds,
        @Positive int bulkheadMaxConcurrent,
        @PositiveOrZero int retryMaxAttempts,
        @Positive int retryBaseMs) {}
