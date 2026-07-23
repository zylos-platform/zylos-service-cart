package app.zylos.cart.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.catalog.grpc")
public record ZylosCatalogGrpcProperties(
        @Positive(message = "gRPC deadline must be positive")
        int deadlineMs,

        @NotBlank @DefaultValue("zylos-internal") String s2sRegistrationId) {}
