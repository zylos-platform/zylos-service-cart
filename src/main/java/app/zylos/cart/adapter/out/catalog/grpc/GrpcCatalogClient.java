package app.zylos.cart.adapter.out.catalog.grpc;

import java.util.Currency;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.application.port.out.CatalogLookupPort;
import app.zylos.cart.application.port.out.CatalogSnapshot;
import app.zylos.cart.config.ZylosCatalogGrpcProperties;
import app.zylos.cart.domain.vo.Money;
import app.zylos.cart.domain.vo.Sku;
import app.zylos.contracts.zylos.catalog.v1.GetVariantBySkuRequest;
import app.zylos.contracts.zylos.catalog.v1.GetVariantBySkuResponse;
import app.zylos.contracts.zylos.catalog.v1.ProductServiceGrpc;
import app.zylos.contracts.zylos.catalog.v1.VariantSnapshot;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Anti-corruption adapter over the Catalog gRPC contract: the innermost layer of the resilient client.
 * Translates gRPC status into {@link CatalogLookup} — a normal return for authoritative answers
 * (OK / NOT_FOUND), and a thrown {@link CatalogUnavailableException} only for transport failures, which
 * the resilience layer acts on.
 */
@Component
public class GrpcCatalogClient implements CatalogLookupPort {

    private final ProductServiceGrpc.ProductServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcCatalogClient(
            ProductServiceGrpc.ProductServiceBlockingStub catalogBlockingStub, ZylosCatalogGrpcProperties properties) {
        this.stub = catalogBlockingStub;
        this.deadlineMs = properties.deadlineMs();
    }

    @Override
    public CatalogLookup lookup(Sku sku) {
        try {
            GetVariantBySkuResponse response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .getVariantBySku(GetVariantBySkuRequest.newBuilder()
                            .setSku(sku.value())
                            .build());
            VariantSnapshot variant = response.getVariant();

            if (!variant.getPurchasable()) {
                return new CatalogLookup.NotPurchasable(sku);
            }

            return new CatalogLookup.Found(new CatalogSnapshot(
                    sku,
                    Money.ofMinor(
                            variant.getListPrice().getMinorUnits(),
                            Currency.getInstance(variant.getListPrice().getCurrencyCode())),
                    variant.getSellerId(),
                    variant.getCatalogVersion()));
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND) {
                return new CatalogLookup.NotFound(sku);
            }

            throw new CatalogUnavailableException("Catalog lookup failed for %s: %s".formatted(sku, code), e);
        }
    }
}
