package com.ecommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.session.DefaultWebSessionManager;
import org.springframework.web.server.session.WebSessionManager;

import com.ecommerce.gateway.security.KeycloakRealmRoleConverter;

import reactor.core.publisher.Mono;

/**
 * The gateway is stateless: no request may open a WebSession. Each one costs a SecureRandom id generated on the
 * boundedElastic pool, which under load put ~700 ms in front of every routed request (Performance Report §8.2).
 */
@SpringBootTest
@Import(GatewaySessionTest.CountingSessions.class)
class GatewaySessionTest {

    private static final DownstreamStub DOWNSTREAM = DownstreamStub.start();
    private static final AtomicInteger SESSIONS_OPENED = new AtomicInteger();

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
        SESSIONS_OPENED.set(0);
    }

    @Test
    @DisplayName("Public, authenticated and rejected requests never open a WebSession")
    void shouldNotOpenSession_forAnyRequest() {
        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/products").exchange().expectStatus().isUnauthorized();
        client.mutateWith(mockJwt()
                        .jwt(jwt -> jwt.claim("realm_access", Map.of("roles", List.of("CUSTOMER"))))
                        .authorities(new KeycloakRealmRoleConverter()))
                .post().uri("/api/v1/orders").exchange().expectStatus().isOk();

        assertThat(SESSIONS_OPENED).hasValue(0);
    }

    @TestConfiguration
    static class CountingSessions {

        /**
         * Picked up by name by the WebFlux handler. The exchange asks for the session Mono up front, so only a
         * subscription (someone actually calling exchange.getSession()) counts as opening one.
         */
        @Bean
        WebSessionManager webSessionManager() {
            DefaultWebSessionManager delegate = new DefaultWebSessionManager();
            return exchange -> Mono.defer(() -> {
                SESSIONS_OPENED.incrementAndGet();
                return delegate.getSession(exchange);
            });
        }
    }
}
