package com.ecommerce.order.infrastructure.scheduler;

import com.ecommerce.order.application.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Ends sagas that never completed. Order-service owns this decision because it is the only
 * participant that knows the order's state; inventory then releases stock on OrderCancelled.
 */
@Component
public class PendingOrderSweeper {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderSweeper.class);

    private final OrderService orderService;

    @Value("${order.pending-timeout-minutes:10}")
    private int pendingTimeoutMinutes;

    public PendingOrderSweeper(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${order.pending-sweeper.fixed-delay-ms:60000}")
    public void cancelStuckOrders() {
        try {
            int cancelled = orderService.cancelOrdersPendingLongerThan(Duration.ofMinutes(pendingTimeoutMinutes));
            if (cancelled > 0) {
                log.info("Pending sweeper cancelled {} orders stuck longer than {} minutes",
                        cancelled, pendingTimeoutMinutes);
            }
        } catch (Exception e) {
            log.error("Pending order sweeper failed: {}", e.getMessage(), e);
        }
    }
}
