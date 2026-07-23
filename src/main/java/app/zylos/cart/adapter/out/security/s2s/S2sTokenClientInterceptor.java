package app.zylos.cart.adapter.out.security.s2s;

import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.stereotype.Component;

import app.zylos.cart.config.ZylosCatalogGrpcProperties;

import io.grpc.*;

/**
 * Attaches a machine-to-machine bearer token to every outbound Catalog call. The token is minted by
 * the {@code zylos-internal}.
 */
@Component
public class S2sTokenClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final OAuth2AuthorizedClientManager clientManager;
    private final String registrationId;

    public S2sTokenClientInterceptor(
            OAuth2AuthorizedClientManager s2sAuthorizedClientManager, ZylosCatalogGrpcProperties properties) {
        this.clientManager = s2sAuthorizedClientManager;
        this.registrationId = properties.s2sRegistrationId();
    }

    @Override
    public <R, S> ClientCall<R, S> interceptCall(MethodDescriptor<R, S> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<S> responseListener, Metadata headers) {
                OAuth2AuthorizedClient client =
                        clientManager.authorize(OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                                .principal(registrationId)
                                .build());

                if (client != null) {
                    headers.put(
                            AUTHORIZATION, "Bearer " + client.getAccessToken().getTokenValue());
                }
                super.start(responseListener, headers);
            }
        };
    }
}
