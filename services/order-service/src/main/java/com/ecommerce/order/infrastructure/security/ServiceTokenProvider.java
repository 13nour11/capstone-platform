package com.ecommerce.order.infrastructure.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * FR-14: the order-service's own access token (Keycloak client credentials, realm role SERVICE) for
 * calls made on its own behalf, never the customer's token. The token is cached and only fetched
 * again shortly before it expires, so a stock check normally costs no extra round trip to Keycloak.
 * <p>
 * Off by default ({@code order.service-auth.enabled}); it is switched on together with inventory's
 * {@code require-service-token} once the deployment passes the client secret to order-service.
 */
@Component
public class ServiceTokenProvider {

    static final String REGISTRATION_ID = "order-service";

    private final boolean enabled;
    private final OAuth2AuthorizedClientManager clientManager;

    public ServiceTokenProvider(@Value("${order.service-auth.enabled:false}") boolean enabled,
                                ObjectProvider<ClientRegistrationRepository> registrations,
                                ObjectProvider<OAuth2AuthorizedClientService> authorizedClients) {
        this.enabled = enabled;
        if (!enabled) {
            this.clientManager = null;
            return;
        }
        ClientRegistrationRepository registrationRepository = registrations.getIfAvailable();
        OAuth2AuthorizedClientService clientService = authorizedClients.getIfAvailable();
        if (registrationRepository == null || clientService == null
                || registrationRepository.findByRegistrationId(REGISTRATION_ID) == null) {
            throw new IllegalStateException("order.service-auth.enabled=true but no OAuth2 client registration '"
                    + REGISTRATION_ID + "' is configured (spring.security.oauth2.client.registration)");
        }
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrationRepository, clientService);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        this.clientManager = manager;
    }

    /** The bearer token to send, or empty while service authentication is switched off. */
    public Optional<String> serviceToken() {
        if (!enabled) {
            return Optional.empty();
        }
        OAuth2AuthorizedClient client = clientManager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId(REGISTRATION_ID).principal(REGISTRATION_ID).build());
        if (client == null) {
            throw new IllegalStateException("Keycloak issued no token for client " + REGISTRATION_ID);
        }
        return Optional.of(client.getAccessToken().getTokenValue());
    }
}
