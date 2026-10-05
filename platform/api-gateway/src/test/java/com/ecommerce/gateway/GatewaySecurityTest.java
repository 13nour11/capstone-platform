package com.ecommerce.gateway;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;

import com.ecommerce.gateway.security.KeycloakRealmRoleConverter;

@SpringBootTest
class GatewaySecurityTest {

    private static final DownstreamStub DOWNSTREAM = DownstreamStub.start();

    @DynamicPropertySource
    static void routeEverythingToStub(DynamicPropertyRegistry registry) {
        String route = "spring.cloud.gateway.server.webflux.routes[0].";
        registry.add(route + "id", () -> "all-apis");
        registry.add(route + "uri", DOWNSTREAM::url);
        registry.add(route + "predicates[0]", () -> "Path=/api/v1/**");
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop();
    }

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }

    @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
    @CsvSource({
            "GET,    /api/v1/products,             ANONYMOUS, 200",
            "GET,    /api/v1/products/1,           ANONYMOUS, 200",
            "POST,   /api/v1/products,             ANONYMOUS, 401",
            "POST,   /api/v1/products,             CUSTOMER,  403",
            "POST,   /api/v1/products,             ADMIN,     200",
            "PUT,    /api/v1/products/1,           CUSTOMER,  403",
            "DELETE, /api/v1/products/1,           ADMIN,     200",
            "POST,   /api/v1/orders,               ANONYMOUS, 401",
            "POST,   /api/v1/orders,               CUSTOMER,  200",
            "GET,    /api/v1/orders/1,             ADMIN,     403",
            "GET,    /api/v1/inventory/1,          CUSTOMER,  403",
            "PUT,    /api/v1/inventory/1,          ADMIN,     200",
            "GET,    /api/v1/inventory/check,      ADMIN,     403",
            "GET,    /api/v1/inventory/check,      SERVICE,   403",
            "POST,   /api/v1/payments,             CUSTOMER,  403",
            "POST,   /api/v1/payments/1/refund,    ADMIN,     200",
            "GET,    /api/v1/analytics/summary,    ADMIN,     200",
            "GET,    /api/v1/unknown,              ADMIN,     403",
    })
    void shouldApplyRoleRule_whenCallingPath(HttpMethod method, String path, String role, int expectedStatus) {
        WebTestClient caller = "ANONYMOUS".equals(role) ? client : client.mutateWith(jwtWithRoles(role));

        caller.method(method).uri(path).exchange().expectStatus().isEqualTo(expectedStatus);
    }

    @Test
    void shouldReturnProblemJson_whenTokenIsMissing() {
        client.post().uri("/api/v1/products").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.instance").isEqualTo("/api/v1/products");
    }

    @Test
    void shouldReturn401_whenTokenIsMalformed() {
        client.get().uri("/api/v1/orders").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void shouldReturnProblemJson_whenRoleIsMissing() {
        client.mutateWith(jwtWithRoles("CUSTOMER")).delete().uri("/api/v1/products/1").exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void shouldReplaceSpoofedUserHeaders_whenAuthenticated() {
        client.mutateWith(jwtWithRoles("CUSTOMER")).get().uri("/api/v1/orders")
                .header("X-User-Id", "attacker")
                .header("X-User-Roles", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Echo-X-User-Id", "user-1")
                .expectHeader().valueEquals("Echo-X-User-Roles", "CUSTOMER");
    }

    @Test
    void shouldStripUserHeaders_whenAnonymous() {
        client.get().uri("/api/v1/products")
                .header("X-User-Id", "attacker")
                .header("x-user-roles", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Echo-X-User-Id")
                .expectHeader().doesNotExist("Echo-X-User-Roles");
    }

    private static JwtMutator jwtWithRoles(String... roles) {
        return mockJwt()
                .jwt(jwt -> jwt.subject("user-1").claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
