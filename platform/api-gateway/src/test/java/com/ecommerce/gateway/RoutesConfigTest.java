package com.ecommerce.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/** Loads the real config-repo/api-gateway.yml so a typo in a route breaks the build, not the demo. */
@SpringBootTest(properties = "spring.config.import=file:../../config-repo/api-gateway.yml")
class RoutesConfigTest {

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/v1/products,            lb://product-service",
            "/api/v1/products/7,          lb://product-service",
            "/api/v1/orders,              lb://order-service",
            "/api/v1/orders/42,           lb://order-service",
            "/api/v1/analytics/summary,   lb://order-service",
            "/api/v1/inventory/7,         lb://inventory-service",
            "/api/v1/payments/1/refund,   lb://payment-service",
    })
    void shouldRouteToOwningService_whenPathMatches(String path, String expectedUri) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));

        Route route = routeLocator.getRoutes().filterWhen(r -> r.getPredicate().apply(exchange)).blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getUri()).hasToString(expectedUri);
    }

    @Test
    void shouldRateLimitPublicProductRoute() {
        RouteDefinition products = routeDefinitionLocator.getRouteDefinitions()
                .filter(definition -> definition.getId().equals("product-service"))
                .blockFirst();

        assertThat(products).isNotNull();
        assertThat(products.getFilters()).extracting(FilterDefinition::getName).contains("RequestRateLimiter");
    }
}
