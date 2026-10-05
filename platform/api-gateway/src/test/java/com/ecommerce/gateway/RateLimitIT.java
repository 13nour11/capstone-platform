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
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

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

        // The bucket refills one token per second, so a fixed two-request sequence passes or fails
        // depending on which side of a second boundary it lands. Spend the budget in a burst and
        // assert on the first rejection instead.
        EntityExchangeResult<byte[]> rejected = null;
        for (int attempt = 0; attempt < 10 && rejected == null; attempt++) {
            EntityExchangeResult<byte[]> result = client.get().uri("/api/v1/products").exchange()
                    .expectBody().returnResult();
            if (result.getStatus().value() == 429) {
                rejected = result;
            }
        }

        assertThat(rejected)
                .withFailMessage("no request was rate limited within 10 attempts")
                .isNotNull();
        assertThat(rejected.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(rejected.getResponseHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(new String(rejected.getResponseBodyContent(), StandardCharsets.UTF_8))
                .contains("\"status\":429")
                .contains("\"code\":\"RATE_LIMITED\"");
    }
}
