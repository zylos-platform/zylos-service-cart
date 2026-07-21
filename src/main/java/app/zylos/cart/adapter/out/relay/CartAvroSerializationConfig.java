package app.zylos.cart.adapter.out.relay;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;

@Configuration(proxyBeanMethods = false)
public class CartAvroSerializationConfig {

    @Bean
    KafkaAvroSerializer cartEventAvroSerializer(ZylosKafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>();
        config.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, kafkaProperties.schemaRegistryUrl());
        config.put(AbstractKafkaSchemaSerDeConfig.AUTO_REGISTER_SCHEMAS, kafkaProperties.autoRegisterSchemas());
        config.put("avro.remove.java.properties", true);

        KafkaAvroSerializer serializer = new KafkaAvroSerializer();
        serializer.configure(config, false);
        return serializer;
    }
}
