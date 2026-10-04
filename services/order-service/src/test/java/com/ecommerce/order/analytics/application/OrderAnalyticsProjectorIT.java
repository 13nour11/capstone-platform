package com.ecommerce.order.analytics.application;

import com.ecommerce.order.analytics.domain.AnalyticsOrderStatus;
import com.ecommerce.order.analytics.domain.AnalyticsSummary;
import com.ecommerce.order.analytics.infrastructure.AnalyticsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Bonus B2 projection on a real PostgreSQL with the service's Flyway migrations (V1 + V50). */
@JdbcTest(properties = "spring.flyway.enabled=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({OrderAnalyticsProjector.class, AnalyticsQueryService.class, AnalyticsRepository.class,
        OrderAnalyticsProjectorIT.Metrics.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderAnalyticsProjectorIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Metrics {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private OrderAnalyticsProjector projector;

    @Autowired
    private AnalyticsQueryService queryService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void cleanReadModel() {
        jdbcClient.sql("DELETE FROM analytics_order").update();
        jdbcClient.sql("DELETE FROM analytics_processed_event").update();
        meterRegistry.clear();
    }

    @Test
    void shouldCountRevenue_whenPlacedOrderIsConfirmed() {
        projector.onOrderPlaced(eventId(), "order-1", new BigDecimal("120.00"), Instant.now());
        projector.onOrderClosed(eventId(), "order-1", AnalyticsOrderStatus.CONFIRMED);

        AnalyticsSummary summary = queryService.summary(Duration.ofHours(1));

        assertThat(summary.totalOrders()).isEqualTo(1);
        assertThat(summary.confirmed()).isEqualTo(1);
        assertThat(summary.revenue()).isEqualByComparingTo("120.00");
        assertThat(orders("PLACED")).isEqualTo(1.0);
        assertThat(orders("CONFIRMED")).isEqualTo(1.0);
        assertThat(revenue()).isEqualTo(120.0);
    }

    @Test
    void shouldNotDoubleCount_whenEventIsRedelivered() {
        String placed = eventId();
        String confirmed = eventId();

        projector.onOrderPlaced(placed, "order-1", new BigDecimal("40.00"), Instant.now());
        projector.onOrderPlaced(placed, "order-1", new BigDecimal("40.00"), Instant.now());
        projector.onOrderClosed(confirmed, "order-1", AnalyticsOrderStatus.CONFIRMED);
        projector.onOrderClosed(confirmed, "order-1", AnalyticsOrderStatus.CONFIRMED);

        AnalyticsSummary summary = queryService.summary(Duration.ofHours(1));
        assertThat(summary.totalOrders()).isEqualTo(1);
        assertThat(summary.revenue()).isEqualByComparingTo("40.00");
        assertThat(orders("PLACED")).isEqualTo(1.0);
        assertThat(orders("CONFIRMED")).isEqualTo(1.0);
        assertThat(revenue()).isEqualTo(40.0);
    }

    @Test
    void shouldKeepFinalStatusAndAmount_whenConfirmationArrivesBeforePlacement() {
        projector.onOrderClosed(eventId(), "order-1", AnalyticsOrderStatus.CONFIRMED);
        projector.onOrderPlaced(eventId(), "order-1", new BigDecimal("15.50"), Instant.now());

        AnalyticsSummary summary = queryService.summary(Duration.ofHours(1));
        assertThat(summary.confirmed()).isEqualTo(1);
        assertThat(summary.pending()).isZero();
        assertThat(summary.revenue()).isEqualByComparingTo("15.50");
        assertThat(revenue()).isEqualTo(15.5);
    }

    @Test
    void shouldIgnoreCancellation_whenOrderIsAlreadyConfirmed() {
        projector.onOrderPlaced(eventId(), "order-1", new BigDecimal("10.00"), Instant.now());
        projector.onOrderClosed(eventId(), "order-1", AnalyticsOrderStatus.CONFIRMED);
        projector.onOrderClosed(eventId(), "order-1", AnalyticsOrderStatus.CANCELLED);

        AnalyticsSummary summary = queryService.summary(Duration.ofHours(1));
        assertThat(summary.confirmed()).isEqualTo(1);
        assertThat(summary.cancelled()).isZero();
        assertThat(orders("CANCELLED")).isZero();
    }

    @Test
    void shouldReportCancelledRatio_whenSomeOrdersAreCancelled() {
        for (int i = 1; i <= 4; i++) {
            projector.onOrderPlaced(eventId(), "order-" + i, new BigDecimal("10.00"), Instant.now());
        }
        projector.onOrderClosed(eventId(), "order-1", AnalyticsOrderStatus.CONFIRMED);
        projector.onOrderClosed(eventId(), "order-2", AnalyticsOrderStatus.CANCELLED);

        AnalyticsSummary summary = queryService.summary(Duration.ofHours(1));
        assertThat(summary.totalOrders()).isEqualTo(4);
        assertThat(summary.pending()).isEqualTo(2);
        assertThat(summary.cancelledRatio()).isEqualByComparingTo("0.25");
        assertThat(summary.hourly()).hasSize(1);
    }

    @Test
    void shouldExcludeOrdersPlacedBeforeTheWindow() {
        projector.onOrderPlaced(eventId(), "old-order", new BigDecimal("99.00"), Instant.now().minus(Duration.ofHours(3)));
        projector.onOrderPlaced(eventId(), "new-order", new BigDecimal("1.00"), Instant.now());

        assertThat(queryService.summary(Duration.ofHours(1)).totalOrders()).isEqualTo(1);
        assertThat(queryService.summary(Duration.ofHours(4)).totalOrders()).isEqualTo(2);
    }

    private double orders(String status) {
        var counter = meterRegistry.find(OrderAnalyticsProjector.ORDERS_METRIC).tag("status", status).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double revenue() {
        var counter = meterRegistry.find(OrderAnalyticsProjector.REVENUE_METRIC).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static String eventId() {
        return UUID.randomUUID().toString();
    }
}
