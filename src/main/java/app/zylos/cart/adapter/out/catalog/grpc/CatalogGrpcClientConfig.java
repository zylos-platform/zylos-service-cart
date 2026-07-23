package app.zylos.cart.adapter.out.catalog.grpc;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

import app.zylos.cart.adapter.out.security.s2s.S2sTokenClientInterceptor;
import app.zylos.contracts.zylos.catalog.v1.ProductServiceGrpc;

/**
 * Builds the Catalog blocking stub over the named "catalog" channel, with the S2S bearer-token
 * interceptor attached.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogGrpcClientConfig {

    @Bean
    ProductServiceGrpc.ProductServiceBlockingStub catalogBlockingStub(
            GrpcChannelFactory channels, S2sTokenClientInterceptor s2sInterceptor) {
        return ProductServiceGrpc.newBlockingStub(channels.createChannel("catalog"))
                .withInterceptors(s2sInterceptor);
    }
}
