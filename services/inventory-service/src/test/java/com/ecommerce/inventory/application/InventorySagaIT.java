package com.ecommerce.inventory.application;

import com.ecommerce.inventory.domain.Reservation;
import com.ecommerce.inventory.domain.ReservationStatus;
import com.ecommerce.inventory.domain.Stock;
import com.ecommerce.inventory.domain.event.OrderItemPayload;
import com.ecommerce.inventory.domain.event.OrderPlaced;
import com.ecommerce.inventory.infrastructure.persistence.ReservationRepository;
import com.ecommerce.inventory.infrastructure.persistence.StockRepository;
import com.ecommerce.inventory.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The inventory Saga steps on a real PostgreSQL (Flyway V1-V3): FR-07 compensation, NFR-05, idempotency, B4. */
@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "inventory.low-stock.threshold=5"
})
class InventorySagaIT {

    /** No broker in this test: the outbox poller must not open a real producer (it would block JVM shutdown). */
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private StockRepository stock;

    @Autowired
    private ReservationRepository reservations;

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("FR-07: a two-product order reserves both lines, and PaymentFailed releases both")
    void shouldReserveAndReleaseEveryLine_ofMultiProductOrder() {
        setStock(301L, 20);
        setStock(302L, 20);
        String orderId = "ord-" + UUID.randomUUID();

        inventory.processOrderPlaced(order(orderId, line(301L, 2), line(302L, 3)));

        assertThat(available(301L)).isEqualTo(18);
        assertThat(available(302L)).isEqualTo(17);
        assertThat(outboxCount(orderId, "InventoryReserved")).isEqualTo(1);

        inventory.releaseReservation(UUID.randomUUID().toString(), orderId, "PAYMENT_FAILED");

        assertThat(available(301L)).isEqualTo(20);
        assertThat(available(302L)).isEqualTo(20);
        assertThat(reservations.findByOrderId(orderId)).extracting(Reservation::getStatus)
                .containsOnly(ReservationStatus.RELEASED);
        assertThat(outboxCount(orderId, "InventoryReleased")).isEqualTo(1);
        assertThat(nfr05OrphanCount()).isZero();
    }

    @Test
    @DisplayName("NFR-10: a redelivered OrderPlaced reserves nothing more")
    void shouldReserveOnce_whenOrderPlacedIsRedelivered() {
        setStock(303L, 10);
        String orderId = "ord-" + UUID.randomUUID();
        OrderPlaced event = order(orderId, line(303L, 4));

        inventory.processOrderPlaced(event);
        inventory.processOrderPlaced(event);

        assertThat(available(303L)).isEqualTo(6);
        assertThat(reservations.findByOrderId(orderId)).hasSize(1);
    }

    @Test
    @DisplayName("NFR-01/05: a slow payment never loses the reservation; a cancelled order's reservation is swept")
    void shouldKeepPendingReservation_andSweepCancelledOne() {
        setStock(304L, 10);
        String pending = "ord-" + UUID.randomUUID();
        inventory.processOrderPlaced(order(pending, line(304L, 1)));

        assertThat(inventory.releaseReservationsOfCancelledOrders()).isZero();
        assertThat(reservations.findByOrderId(pending)).extracting(Reservation::getStatus)
                .containsExactly(ReservationStatus.RESERVED);

        String late = "ord-" + UUID.randomUUID();
        inventory.releaseReservation(UUID.randomUUID().toString(), late, "PAYMENT_FAILED");
        inventory.processOrderPlaced(order(late, line(304L, 1)));
        assertThat(reservations.findByOrderId(late)).isEmpty();
        assertThat(available(304L)).isEqualTo(9);
    }

    @Test
    @DisplayName("B4: crossing the threshold queues one LowStock, staying low queues none, restocking re-arms")
    void shouldQueueLowStockOncePerDrop() {
        setStock(305L, 7);

        inventory.processOrderPlaced(order("ord-" + UUID.randomUUID(), line(305L, 3)));   // 7 -> 4: alert
        inventory.processOrderPlaced(order("ord-" + UUID.randomUUID(), line(305L, 1)));   // 4 -> 3: no alert
        inventory.adjustStock(305L, 50);                                                  // back to normal
        inventory.adjustStock(305L, 2);                                                   // drops again: alert

        assertThat(jdbc.sql("select count(*) from outbox_event where event_type = 'LowStock' and aggregate_id = '305'")
                .query(Long.class).single()).isEqualTo(2);
        assertThat(inventory.getLowStock()).extracting(s -> s.productId()).contains(305L);
    }

    private void setStock(long productId, int available) {
        stock.save(Stock.builder().productId(productId).available(available).reserved(0).build());
    }

    private int available(long productId) {
        return stock.findById(productId).orElseThrow().getAvailable();
    }

    private long outboxCount(String orderId, String eventType) {
        return jdbc.sql("select count(*) from outbox_event where aggregate_id = ? and event_type = ?")
                .params(orderId, eventType).query(Long.class).single();
    }

    /** The NFR-05 evidence query from the ADD, without the 30-second grace period. */
    private long nfr05OrphanCount() {
        return jdbc.sql("""
                select count(*) from reservation r join cancelled_order c on c.order_id = r.order_id
                where r.status = 'RESERVED'""").query(Long.class).single();
    }

    private static OrderItemPayload line(long productId, int quantity) {
        return new OrderItemPayload(productId, quantity, new BigDecimal("10.00"));
    }

    private static OrderPlaced order(String orderId, OrderItemPayload... items) {
        return new OrderPlaced(UUID.randomUUID().toString(), orderId, "cust-1", new BigDecimal("50.00"),
                List.of(items), Instant.now());
    }
}
