package com.ecommerce.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class RateLimitIT {

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final DownstreamStub DOWNSTREAM = DownstreamStub.start();

    @DynamicPropertySource
    static void rateLimitedRoute(DynamicPropertyRegistry registry) {
        String route = "spring.cloud.gateway.server.webflux.routes[0].";
        registry.add(route + "id", () -> "products");
        registry.add(route + "uri", DOWNSTREAM::url);
        registry.add(route + "predicates[0]", () -> "Path=/api/v1/products/**");
        registry.add(route + "filters[0].name", () -> "RequestRateLimiter");
        registry.add(route + "filters[0].args[key-resolver]", () -> "#{@userOrIpKeyResolver}");
        registry.add(route + "filters[0].args[redis-rate-limiter.replenishRate]", () -> "1");
        registry.add(route + "filters[0].args[redis-rate-limiter.burstCapacity]", () -> "1");
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.stop();
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void shouldReturn429ProblemJson_whenClientExceedsItsBucket() {
        WebTestClient client = WebTestClient.bindToApplicationContext(context).build();

        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();

        client.get().uri("/api/v1/products").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.code").isEqualTo("RATE_LIMITED");
    }
}
