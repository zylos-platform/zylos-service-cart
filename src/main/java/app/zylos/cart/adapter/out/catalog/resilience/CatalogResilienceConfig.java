package app.zylos.cart.adapter.out.catalog.resilience;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import app.zylos.cart.adapter.out.catalog.grpc.CatalogUnavailableException;
import app.zylos.cart.config.ZylosCatalogResilienceProperties;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Builds the Catalog resilience primitives functionally: a count-based circuit breaker, a
 * semaphore bulkhead that fails fast when saturated, and a retry with full-jitter exponential backoff.
 * Only {@link CatalogUnavailableException} is recorded/retried — authoritative negatives are successes.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogResilienceConfig {

    private static final String CATALOG_SERVICE = "catalog";

    private final ZylosCatalogResilienceProperties properties;

    CatalogResilienceConfig(ZylosCatalogResilienceProperties properties) {
        this.properties = properties;
    }

    @Bean
    CircuitBreaker catalogCircuitBreaker() {

        return CircuitBreaker.of(
                CATALOG_SERVICE,
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(properties.cbSlidingWindow())
                        .failureRateThreshold(properties.cbFailureRateThreshold())
                        .waitDurationInOpenState(Duration.ofSeconds(properties.cbWaitOpenSeconds()))
                        .permittedNumberOfCallsInHalfOpenState(3)
                        .recordExceptions(CatalogUnavailableException.class)
                        .build());
    }

    @Bean
    Bulkhead catalogBulkhead() {
        return Bulkhead.of(
                CATALOG_SERVICE,
                BulkheadConfig.custom()
                        .maxConcurrentCalls(properties.bulkheadMaxConcurrent())
                        .maxWaitDuration(Duration.ZERO)
                        .build());
    }

    @Bean
    Retry catalogRetry() {
        return Retry.of(
                CATALOG_SERVICE,
                RetryConfig.custom()
                        .maxAttempts(properties.retryMaxAttempts())
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                                Duration.ofMillis(properties.retryBaseMs()), 2.0, 0.5))
                        .retryExceptions(CatalogUnavailableException.class)
                        .build());
    }
}
