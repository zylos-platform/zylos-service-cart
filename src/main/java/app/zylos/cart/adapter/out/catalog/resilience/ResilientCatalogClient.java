package app.zylos.cart.adapter.out.catalog.resilience;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import app.zylos.cart.adapter.out.catalog.grpc.CatalogUnavailableException;
import app.zylos.cart.adapter.out.catalog.grpc.GrpcCatalogClient;
import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.application.port.out.CatalogLookupPort;
import app.zylos.cart.domain.vo.Sku;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;

/**
 * Wraps the gRPC client in {@code Retry(Bulkhead(CircuitBreaker(call)))}. The circuit breaker is
 * innermost so it records every attempt; the bulkhead caps concurrency; retry is outermost. Because
 * authoritative negatives are returned (never thrown), a missing SKU is a success and never opens the
 * breaker — only transport failures do. When the breaker is open, the bulkhead is full, or retries are
 * exhausted, the call degrades to {@link CatalogLookup.Unavailable}.
 */
@Component
public class ResilientCatalogClient implements CatalogLookupPort {

    private final GrpcCatalogClient delegate;
    private final CircuitBreaker circuitBreaker;
    private final Bulkhead bulkhead;
    private final Retry retry;

    public ResilientCatalogClient(
            GrpcCatalogClient delegate,
            CircuitBreaker catalogCircuitBreaker,
            Bulkhead catalogBulkhead,
            Retry catalogRetry) {
        this.delegate = delegate;
        this.circuitBreaker = catalogCircuitBreaker;
        this.bulkhead = catalogBulkhead;
        this.retry = catalogRetry;
    }

    @Override
    public CatalogLookup lookup(Sku sku) {
        Supplier<CatalogLookup> guarded = Retry.decorateSupplier(
                retry,
                Bulkhead.decorateSupplier(
                        bulkhead, CircuitBreaker.decorateSupplier(circuitBreaker, () -> delegate.lookup(sku))));
        try {
            return guarded.get();
        } catch (CallNotPermittedException | BulkheadFullException | CatalogUnavailableException _) {
            return new CatalogLookup.Unavailable(sku);
        }
    }
}
