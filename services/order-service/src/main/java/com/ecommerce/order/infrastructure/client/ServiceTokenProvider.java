package com.ecommerce.order.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * FR-14: order-service authenticates its calls to inventory-service with its own Keycloak client
 * (client credentials, realm role SERVICE), never with the customer's token. The token is cached until shortly
 * before it expires. Without a configured token endpoint (unit and slice tests) no token is sent.
 */
@Component
public class ServiceTokenProvider {

    private static final long EXPIRY_MARGIN_SECONDS = 30;

    private final RestClient restClient;
    private final String tokenUri;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;

    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    public ServiceTokenProvider(RestClient.Builder restClientBuilder,
                                @Value("${order.service-auth.token-uri:}") String tokenUri,
                                @Value("${order.service-auth.client-id:order-service}") String clientId,
                                @Value("${order.service-auth.client-secret:}") String clientSecret) {
        this.restClient = restClientBuilder.build();
        this.tokenUri = tokenUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = Clock.systemUTC();
    }

    public synchronized Optional<String> accessToken() {
        if (tokenUri.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (cachedToken == null || now.isAfter(expiresAt)) {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", clientId);
            form.add("client_secret", clientSecret);
            TokenResponse response = restClient.post().uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new IllegalStateException("Keycloak returned no access token for " + clientId);
            }
            cachedToken = response.accessToken();
            expiresAt = now.plusSeconds(Math.max(0, response.expiresIn() - EXPIRY_MARGIN_SECONDS));
        }
        return Optional.of(cachedToken);
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) {
    }
}
