package com.ecommerce.inventory.infrastructure.scheduler;

import com.ecommerce.inventory.application.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationSweeper.class);

    private final InventoryService inventoryService;

    @Value("${inventory.reservation.ttl-seconds:30}")
    private int ttlSeconds;

    public ReservationSweeper(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Scheduled(fixedDelayString = "${inventory.sweeper.fixed-delay-ms:10000}")
    public void sweepExpiredReservations() {
        try {
            int releasedCount = inventoryService.releaseExpiredReservations(ttlSeconds);
            if (releasedCount > 0) {
                log.info("NFR-05 Sweeper executed: released {} expired reservations older than {} seconds",
                        releasedCount, ttlSeconds);
            }
        } catch (Exception e) {
            log.error("Error during reservation sweeper execution: {}", e.getMessage(), e);
        }
    }
}
