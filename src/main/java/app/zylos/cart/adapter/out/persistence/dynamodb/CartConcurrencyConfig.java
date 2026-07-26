package app.zylos.cart.adapter.out.persistence.dynamodb;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.zylos.cart.application.port.out.OptimisticConcurrencyException;
import app.zylos.cart.config.ZylosCartConcurrencyProperties;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

@Configuration(proxyBeanMethods = false)
public class CartConcurrencyConfig {

    private final ZylosCartConcurrencyProperties properties;

    public CartConcurrencyConfig(ZylosCartConcurrencyProperties properties) {
        this.properties = properties;
    }

    @Bean
    Retry cartOptimisticRetry() {
        return Retry.of(
                "cart-optimistic",
                RetryConfig.custom()
                        .maxAttempts(properties.retry().maxAttempts())
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                                Duration.ofMillis(properties.retry().baseMs()), 2.0, 0.5))
                        .retryExceptions(OptimisticConcurrencyException.class)
                        .failAfterMaxAttempts(true)
                        .build());
    }
}
