package com.ecommerce.order.analytics.application;

import com.ecommerce.order.analytics.domain.AnalyticsOrderStatus;
import com.ecommerce.order.analytics.infrastructure.AnalyticsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Applies order events to the analytics read model exactly once per event.
 * Metrics move only after commit, so a rolled-back or redelivered event is never counted.
 */
@Service
public class OrderAnalyticsProjector {

    static final String ORDERS_METRIC = "analytics.orders";
    static final String REVENUE_METRIC = "analytics.revenue";
    static final String PLACED = "PLACED";

    private final AnalyticsRepository repository;
    private final MeterRegistry meterRegistry;

    public OrderAnalyticsProjector(AnalyticsRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void onOrderPlaced(String eventId, String orderId, BigDecimal totalAmount, Instant placedAt) {
        Instant now = Instant.now();
        if (!repository.markProcessed(eventId, now)) {
            return;
        }
        AnalyticsOrderStatus status = repository.recordPlaced(orderId, totalAmount, placedAt, now);
        afterCommit(() -> {
            countOrder(PLACED);
            if (status == AnalyticsOrderStatus.CONFIRMED) {
                countRevenue(totalAmount);
            }
        });
    }

    @Transactional
    public void onOrderClosed(String eventId, String orderId, AnalyticsOrderStatus finalStatus) {
        if (finalStatus == AnalyticsOrderStatus.PENDING) {
            throw new IllegalArgumentException("An order closes as CONFIRMED or CANCELLED, not PENDING");
        }
        Instant now = Instant.now();
        if (!repository.markProcessed(eventId, now)) {
            return;
        }
        repository.close(orderId, finalStatus, now).ifPresent(closed -> afterCommit(() -> {
            countOrder(finalStatus.name());
            if (finalStatus == AnalyticsOrderStatus.CONFIRMED && closed.totalAmount() != null) {
                countRevenue(closed.totalAmount());
            }
        }));
    }

    private void countOrder(String status) {
        meterRegistry.counter(ORDERS_METRIC, "status", status).increment();
    }

    private void countRevenue(BigDecimal amount) {
        meterRegistry.counter(REVENUE_METRIC).increment(amount.doubleValue());
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
