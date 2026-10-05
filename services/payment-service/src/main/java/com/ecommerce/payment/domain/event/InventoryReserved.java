package com.ecommerce.payment.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Consumed from {@code inventory-events}: stock is held for the order, so payment may charge it. */
public record InventoryReserved(String eventId, String orderId, BigDecimal totalAmount, Instant occurredAt) {

    public boolean isComplete() {
        return eventId != null && !eventId.isBlank()
                && orderId != null && !orderId.isBlank()
                && totalAmount != null && totalAmount.signum() > 0;
    }
}
