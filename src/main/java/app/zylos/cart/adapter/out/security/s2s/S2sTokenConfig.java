package app.zylos.cart.adapter.out.security.s2s;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * A non-request-bound authorized-client manager for machine-to-machine tokens: it performs the
 * {@code client_credentials} grant against Keycloak and caches/refreshes the token internally, so the
 * interceptor can ask for a fresh access token on every call cheaply.
 */
@Configuration(proxyBeanMethods = false)
public class S2sTokenConfig {

    @Bean
    OAuth2AuthorizedClientManager s2sAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrations, OAuth2AuthorizedClientService authorizedClients) {
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials()
                .build());
        return manager;
    }
}
