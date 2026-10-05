package com.ecommerce.eurekaserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** L0: eureka-server starts as a standalone registry and answers the registry API services use. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability // tests turn metrics export off by default
class EurekaServerApplicationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    @DisplayName("Answers the registry API that clients register with")
    void shouldServeRegistry() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> response = http.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"applications\"");
    }

    @Test
    @DisplayName("Reports UP on the health endpoint used by Compose and Kubernetes probes")
    void shouldReportHealthy() {
        assertThat(http.getForObject("/actuator/health", String.class)).startsWith("{\"status\":\"UP\"");
    }

    @Test
    @DisplayName("Exposes Prometheus metrics, so the platform-wide scrape covers it")
    void shouldExposePrometheusMetrics() {
        // The Accept header Prometheus sends; config-server would answer a JSON request as a config lookup.
        HttpHeaders scrape = new HttpHeaders();
        scrape.set(HttpHeaders.ACCEPT, "application/openmetrics-text;version=1.0.0,text/plain;version=0.0.4;q=0.5");

        ResponseEntity<String> metrics =
                http.exchange("/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(scrape), String.class);

        assertThat(metrics.getBody()).contains("jvm_memory_used_bytes");
    }
}
