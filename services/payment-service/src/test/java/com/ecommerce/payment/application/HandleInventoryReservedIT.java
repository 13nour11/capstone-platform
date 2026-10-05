package com.ecommerce.payment.application;

import com.ecommerce.payment.domain.event.InventoryReserved;
import com.ecommerce.payment.support.PostgresTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Saga step on a real PostgreSQL: dedup row, payment and outbox row commit together. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
class HandleInventoryReservedIT {

    @Autowired
    private HandleInventoryReservedService service;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldChargeAndRecordPaymentCompleted_whenStockIsReserved() {
        InventoryReserved event = reserved("order-" + UUID.randomUUID());

        service.handle(event);

        assertThat(paymentStatuses(event.orderId())).containsExactly("COMPLETED");
        assertThat(outboxTypes(event.orderId())).containsExactly("PaymentCompleted");
        String payload = outboxPayload(event.orderId());
        assertThat(JsonPath.<String>read(payload, "$.orderId")).isEqualTo(event.orderId());
        assertThat(JsonPath.<Double>read(payload, "$.totalAmount")).isEqualTo(75.5);
    }

    @Test
    void shouldIgnoreRedelivery_whenSameEventArrivesTwice() {
        InventoryReserved event = reserved("order-" + UUID.randomUUID());

        service.handle(event);
        service.handle(event);

        assertThat(paymentStatuses(event.orderId())).hasSize(1);
        assertThat(outboxTypes(event.orderId())).hasSize(1);
    }

    @Test
    void shouldNotChargeTwice_whenOrderGetsANewReservationEvent() {
        String orderId = "order-" + UUID.randomUUID();

        service.handle(reserved(orderId));
        service.handle(reserved(orderId));

        assertThat(paymentStatuses(orderId)).containsExactly("COMPLETED");
        assertThat(outboxTypes(orderId)).containsExactly("PaymentCompleted", "PaymentCompleted");
    }

    private static InventoryReserved reserved(String orderId) {
        return new InventoryReserved(UUID.randomUUID().toString(), orderId, new BigDecimal("75.50"), Instant.now());
    }

    private List<String> paymentStatuses(String orderId) {
        return jdbcClient.sql("SELECT status FROM payments WHERE order_id = ?")
                .param(orderId).query(String.class).list();
    }

    private List<String> outboxTypes(String orderId) {
        return jdbcClient.sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY created_at")
                .param(orderId).query(String.class).list();
    }

    private String outboxPayload(String orderId) {
        return jdbcClient.sql("SELECT payload::text FROM outbox_event WHERE aggregate_id = ?")
                .param(orderId).query(String.class).single();
    }
}
