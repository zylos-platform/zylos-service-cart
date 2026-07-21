package app.zylos.cart.adapter.out.relay;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.dynamodb")
public record ZylosDynamodbProperties(
        @NotBlank String tableName, @NotBlank String endpoint) {}
