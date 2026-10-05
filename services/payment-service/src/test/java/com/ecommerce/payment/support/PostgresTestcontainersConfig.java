package com.ecommerce.payment.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/** Real PostgreSQL for integration tests: ON CONFLICT, SKIP LOCKED and JSONB need the real engine. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
