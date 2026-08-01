package app.zylos.cart.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.cart.outbox")
public record ZylosCartOutboxProperties(
        @Min(value = 1) @Max(value = 1024) @DefaultValue("16")
        int writeShards,

        @Min(value = 1) @Max(value = 1024) @DefaultValue("16")
        int readShards) {
    @AssertTrue(message = "readShards must be greater than or equal to writeShards to prevent silent event loss.")
    public boolean isValidShardConfiguration() {
        return readShards >= writeShards;
    }
}
