package app.zylos.cart.adapter.out.catalog.grpc;

import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.application.port.out.CatalogLookupPort;
import app.zylos.cart.application.port.out.CatalogSnapshot;
import app.zylos.cart.config.ZylosCatalogGrpcProperties;
import app.zylos.cart.domain.vo.Money;
import app.zylos.cart.domain.vo.Sku;
import app.zylos.contracts.zylos.catalog.v1.*;

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
            return toLookup(sku, variant);
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND) {
                return new CatalogLookup.NotFound(sku);
            }

            throw new CatalogUnavailableException("Catalog lookup failed for %s: %s".formatted(sku, code), e);
        }
    }

    @Override
    public Map<Sku, CatalogLookup> lookupAll(Collection<Sku> skus) {
        if (skus.isEmpty()) {
            return Map.of();
        }

        try {
            BatchGetVariantsBySkuResponse response = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .batchGetVariantsBySku(BatchGetVariantsBySkuRequest.newBuilder()
                            .addAllSkus(skus.stream().map(Sku::value).toList())
                            .build());

            Map<String, VariantSnapshot> variants = response.getVariantsMap();
            Map<Sku, CatalogLookup> results = new LinkedHashMap<>();
            for (Sku sku : skus) {
                VariantSnapshot variant = variants.get(sku.value());
                // Absent from the response is an authoritative negative, per the contract.
                results.put(sku, variant == null ? new CatalogLookup.NotFound(sku) : toLookup(sku, variant));
            }
            return results;
        } catch (StatusRuntimeException e) {
            throw new CatalogUnavailableException(
                    "Catalog batch lookup failed (%d SKUs): %s"
                            .formatted(skus.size(), e.getStatus().getCode()),
                    e);
        }
    }

    private static CatalogLookup toLookup(Sku sku, VariantSnapshot variant) {
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
    }
}
