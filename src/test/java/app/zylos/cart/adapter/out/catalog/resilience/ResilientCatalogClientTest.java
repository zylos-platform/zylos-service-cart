package app.zylos.cart.adapter.out.catalog.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Currency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zylos.cart.adapter.out.catalog.grpc.CatalogUnavailableException;
import app.zylos.cart.adapter.out.catalog.grpc.GrpcCatalogClient;
import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.application.port.out.CatalogSnapshot;
import app.zylos.cart.domain.vo.Money;
import app.zylos.cart.domain.vo.Sku;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

class ResilientCatalogClientTest {

    private static final Currency USD = java.util.Currency.getInstance("USD");

    private GrpcCatalogClient grpc;
    private CircuitBreaker circuitBreaker;
    private ResilientCatalogClient client;

    @BeforeEach
    void setUp() {
        grpc = mock(GrpcCatalogClient.class);
        circuitBreaker = CircuitBreaker.of(
                "test",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(4)
                        .minimumNumberOfCalls(4)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .recordExceptions(CatalogUnavailableException.class)
                        .build());
        Bulkhead bulkhead = Bulkhead.of(
                "test", BulkheadConfig.custom().maxConcurrentCalls(8).build());
        Retry retry = Retry.of(
                "test",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(1))
                        .retryExceptions(CatalogUnavailableException.class)
                        .build());
        client = new ResilientCatalogClient(grpc, circuitBreaker, bulkhead, retry);
    }

    private static CatalogLookup found(Sku sku) {
        return new CatalogLookup.Found(new CatalogSnapshot(sku, Money.ofMinor(100, USD), "seller-1", 1L));
    }

    @Test
    void passesThroughASuccessfulLookup() {
        Sku sku = Sku.of("SKU-1");
        when(grpc.lookup(sku)).thenReturn(found(sku));
        assertThat(client.lookup(sku)).isInstanceOf(CatalogLookup.Found.class);
    }

    @Test
    void authoritativeNegativesAreSuccessesAndDoNotOpenTheBreaker() {
        Sku sku = Sku.of("MISSING");
        when(grpc.lookup(sku)).thenReturn(new CatalogLookup.NotFound(sku));

        for (int i = 0; i < 10; i++) {
            assertThat(client.lookup(sku)).isInstanceOf(CatalogLookup.NotFound.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void transportFailuresDegradeToUnavailableAndAreRetried() {
        Sku sku = Sku.of("SKU-1");
        when(grpc.lookup(sku)).thenThrow(new CatalogUnavailableException("down", new RuntimeException()));

        assertThat(client.lookup(sku)).isInstanceOf(CatalogLookup.Unavailable.class);
        verify(grpc, atMost(2)).lookup(sku); // one retry
    }

    @Test
    void anOpenBreakerFailsFastToUnavailableWithoutCallingCatalog() {
        Sku sku = Sku.of("SKU-1");
        when(grpc.lookup(any())).thenThrow(new CatalogUnavailableException("down", new RuntimeException()));
        for (int i = 0; i < 6; i++) {
            client.lookup(sku); // drive failures past the threshold
        }
        circuitBreaker.transitionToOpenState();

        clearInvocations(grpc);
        assertThat(client.lookup(sku)).isInstanceOf(CatalogLookup.Unavailable.class);
        verify(grpc, never()).lookup(sku); // open circuit short-circuits
    }
}
