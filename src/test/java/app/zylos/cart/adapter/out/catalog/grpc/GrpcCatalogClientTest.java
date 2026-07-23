package app.zylos.cart.adapter.out.catalog.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.zylos.cart.application.port.out.CatalogLookup;
import app.zylos.cart.config.ZylosCatalogGrpcProperties;
import app.zylos.cart.domain.vo.Sku;
import app.zylos.contracts.zylos.catalog.v1.GetVariantBySkuRequest;
import app.zylos.contracts.zylos.catalog.v1.GetVariantBySkuResponse;
import app.zylos.contracts.zylos.catalog.v1.ProductServiceGrpc;
import app.zylos.contracts.zylos.catalog.v1.VariantSnapshot;
import app.zylos.contracts.zylos.common.v1.Money;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;

class GrpcCatalogClientTest {

    private Server server;
    private ManagedChannel channel;
    private GrpcCatalogClient client;
    private FakeCatalog fake;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakeCatalog();
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(fake)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();

        ZylosCatalogGrpcProperties properties = new ZylosCatalogGrpcProperties(1000, "test");
        client = new GrpcCatalogClient(ProductServiceGrpc.newBlockingStub(channel), properties);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void mapsAPurchasableVariantToFound() {
        fake.respond = VariantSnapshot.newBuilder()
                .setSku("SKU-1")
                .setProductId("p1")
                .setSellerId("seller-1")
                .setPurchasable(true)
                .setCatalogVersion(3)
                .setListPrice(Money.newBuilder().setMinorUnits(1999).setCurrencyCode("USD"))
                .build();

        CatalogLookup result = client.lookup(Sku.of("SKU-1"));

        assertThat(result).isInstanceOfSatisfying(CatalogLookup.Found.class, found -> {
            assertThat(found.snapshot().sellerId()).isEqualTo("seller-1");
            assertThat(found.snapshot().unitPrice().minorUnits()).isEqualTo(1999L);
            assertThat(found.snapshot().catalogVersion()).isEqualTo(3L);
        });
    }

    @Test
    void mapsANonPurchasableVariantToNotPurchasable() {
        fake.respond = VariantSnapshot.newBuilder()
                .setSku("SKU-2")
                .setPurchasable(false)
                .setListPrice(Money.newBuilder().setMinorUnits(1).setCurrencyCode("USD"))
                .build();

        assertThat(client.lookup(Sku.of("SKU-2"))).isInstanceOf(CatalogLookup.NotPurchasable.class);
    }

    @Test
    void mapsNotFoundStatusToAnAuthoritativeNegative() {
        fake.error = Status.NOT_FOUND;
        assertThat(client.lookup(Sku.of("SKU-3"))).isInstanceOf(CatalogLookup.NotFound.class);
    }

    @Test
    void translatesTransportFailuresIntoCatalogUnavailable() {
        fake.error = Status.UNAVAILABLE;
        assertThatThrownBy(() -> client.lookup(Sku.of("SKU-4"))).isInstanceOf(CatalogUnavailableException.class);
    }

    private static final class FakeCatalog extends ProductServiceGrpc.ProductServiceImplBase {
        VariantSnapshot respond;
        Status error;

        @Override
        public void getVariantBySku(GetVariantBySkuRequest request, StreamObserver<GetVariantBySkuResponse> obs) {
            if (error != null) {
                obs.onError(error.asRuntimeException());
                return;
            }
            obs.onNext(GetVariantBySkuResponse.newBuilder().setVariant(respond).build());
            obs.onCompleted();
        }
    }
}
