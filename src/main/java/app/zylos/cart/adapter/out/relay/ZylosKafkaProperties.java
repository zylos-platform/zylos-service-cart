package app.zylos.cart.adapter.out.relay;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "zylos.kafka")
public record ZylosKafkaProperties(
    @NotBlank
    String schemaRegistryUrl,

    @DefaultValue("true")
    boolean autoRegisterSchemas
) {}
