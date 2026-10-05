package com.ecommerce.inventory.infrastructure.scheduler;

import com.ecommerce.inventory.application.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * NFR-05: no reservation may outlive its cancelled order by more than 30 seconds. Releases normally happen
 * at once (PaymentFailed / OrderCancelled); this sweeper, every 10 s by default, is the safety net.
 */
@Component
public class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);

    private final InventoryService inventoryService;

    public ReservationSweeper(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Scheduled(fixedDelayString = "${inventory.sweeper.fixed-delay-ms:10000}")
    public void sweepReservationsOfCancelledOrders() {
        try {
            inventoryService.releaseReservationsOfCancelledOrders();
        } catch (RuntimeException e) {
            // The next run retries; a failed sweep must not stop the scheduler
            log.error("Error during reservation sweeper execution: {}", e.getMessage(), e);
        }
    }
}
