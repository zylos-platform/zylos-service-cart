package app.zylos.cart.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.cart.expiry")
public record ZylosCartExpiryProperties(
        @Min(value = 1) @Max(value = 1024) @DefaultValue("64")
        int writeShards,

        @Min(value = 1) @Max(value = 1024) @DefaultValue("64")
        int readShards) {}
