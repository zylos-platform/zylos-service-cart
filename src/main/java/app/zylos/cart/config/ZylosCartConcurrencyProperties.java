package app.zylos.cart.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.cart.concurrency")
public record ZylosCartConcurrencyProperties(@Valid Retry retry) {
    public record Retry(
            @Positive @DefaultValue("4") int maxAttempts,

            @Positive @DefaultValue("20") long baseMs) {}
}
