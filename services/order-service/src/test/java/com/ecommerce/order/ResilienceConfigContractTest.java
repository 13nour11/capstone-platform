package com.ecommerce.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The resilience settings live in config-repo (served by the Config Server), which tests do not load.
 * This guards the values the Saga's sync path depends on, so an edit there cannot silently undo them.
 */
class ResilienceConfigContractTest {

    private static final Path CONFIG = Path.of("..", "..", "config-repo", "order-service.yml");

    private static Properties config() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(CONFIG));
        return yaml.getObject();
    }

    @Test
    @DisplayName("Out-of-stock is neither retried nor counted by the inventory circuit breaker")
    void shouldTreatOutOfStockAsABusinessAnswer() {
        Properties p = config();
        String outOfStock = "com.ecommerce.order.domain.exception.OutOfStockException";

        assertThat(p.getProperty("resilience4j.circuitbreaker.instances.inventoryService.ignoreExceptions[0]"))
                .isEqualTo(outOfStock);
        assertThat(p.getProperty("resilience4j.retry.instances.inventoryService.ignoreExceptions[0]"))
                .isEqualTo(outOfStock);
    }

    @Test
    @DisplayName("The inventory call has a bulkhead and a time limit (Feign timeouts)")
    void shouldBoundConcurrencyAndTime() {
        Properties p = config();

        assertThat(p.getProperty("resilience4j.bulkhead.instances.inventoryService.maxConcurrentCalls")).isNotBlank();
        assertThat(p.getProperty("spring.cloud.openfeign.client.config.inventory-service.readTimeout")).isNotBlank();
        assertThat(p.getProperty("spring.cloud.openfeign.client.config.product-service.readTimeout")).isNotBlank();
    }

    @Test
    @DisplayName("Flyway runs out of order, because B (V1-V49) and C (V50+) share one folder")
    void shouldAllowOutOfOrderMigrations() {
        assertThat(config().getProperty("spring.flyway.out-of-order")).isEqualTo("true");
    }
}
