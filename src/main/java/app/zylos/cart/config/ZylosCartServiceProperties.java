package app.zylos.cart.config;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.service")
public record ZylosCartServiceProperties(
        @NotBlank String name,

        @NotBlank @DefaultValue("0.0.0") String version) {}
