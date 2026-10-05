package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.event.InventoryReserved;
import com.ecommerce.payment.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** FR-09 trigger: with failure-rate 1.0 the saga gets PaymentFailed, which drives the compensation. */
@SpringBootTest(properties = "payment.simulation.failure-rate=1.0")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
class DeclinedPaymentIT {

    @Autowired
    private HandleInventoryReservedService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldRecordPaymentFailed_whenProviderDeclines() {
        String orderId = "order-" + UUID.randomUUID();

        service.handle(new InventoryReserved(UUID.randomUUID().toString(), orderId, new BigDecimal("12.00"),
                Instant.now()));

        assertThat(jdbcClient.sql("SELECT status FROM payments WHERE order_id = ?").param(orderId)
                .query(String.class).single()).isEqualTo("FAILED");
        assertThat(jdbcClient.sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ?").param(orderId)
                .query(String.class).single()).isEqualTo("PaymentFailed");
    }
}
