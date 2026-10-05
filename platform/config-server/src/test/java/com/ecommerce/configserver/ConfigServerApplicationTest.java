package com.ecommerce.configserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** L0: config-server starts and serves the repository's config-repo to every service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerApplicationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("Serves order-service.yml together with the shared application.yml")
    void shouldServeServiceAndSharedConfig() {
        ResponseEntity<String> response = http.getForEntity("/order-service/default", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("order-service.yml").contains("application.yml");
    }

    @Test
    @DisplayName("Reports UP on the health endpoint used by Compose and Kubernetes probes")
    void shouldReportHealthy() {
        assertThat(http.getForObject("/actuator/health", String.class)).startsWith("{\"status\":\"UP\"");
    }
}
