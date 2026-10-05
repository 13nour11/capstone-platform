package com.ecommerce.order.infrastructure.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** FR-14: order-service fetches its own client-credentials token and reuses it until it nearly expires. */
class ServiceTokenProviderTest {

    private static final String TOKEN_URI = "http://keycloak/realms/ecommerce-platform/protocol/openid-connect/token";

    @Test
    void shouldRequestClientCredentialsTokenOnce_andReuseIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer keycloak = MockRestServiceServer.bindTo(builder).build();
        keycloak.expect(requestTo(TOKEN_URI))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_id=order-service")))
                .andRespond(withSuccess("{\"access_token\":\"svc-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
        ServiceTokenProvider tokens = new ServiceTokenProvider(builder, TOKEN_URI, "order-service", "secret");

        assertThat(tokens.accessToken()).contains("svc-token");
        assertThat(tokens.accessToken()).contains("svc-token");

        keycloak.verify();
    }

    @Test
    void shouldSendNoToken_whenNoTokenEndpointIsConfigured() {
        ServiceTokenProvider tokens = new ServiceTokenProvider(RestClient.builder(), "", "order-service", "");

        assertThat(tokens.accessToken()).isEmpty();
    }
}
