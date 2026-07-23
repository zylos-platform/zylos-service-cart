package app.zylos.cart.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.catalog.cache")
public record ZylosCatalogCacheProperties(
        @NotBlank String keyPrefix,
        @Positive int foundFreshSeconds,
        @Positive int foundHardSeconds,
        @Positive int negativeFreshSeconds,
        @Positive int negativeHardSeconds) {}
