package app.zylos.cart.adapter.out.catalog.cache;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

@Configuration(proxyBeanMethods = false)
public class CatalogCacheConfig {

    @Bean
    RedisTemplate<String, CatalogCacheEntry> catalogCacheRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, CatalogCacheEntry> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(new JacksonJsonRedisSerializer<>(CatalogCacheEntry.class));
        return template;
    }
}
