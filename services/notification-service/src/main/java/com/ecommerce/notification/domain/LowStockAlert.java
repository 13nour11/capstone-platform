package com.ecommerce.notification.domain;

import java.time.Instant;

/** Bonus B4: a product dropped below its low-stock threshold (from inventory-service's LowStock event). */
public record LowStockAlert(String eventId, long productId, int available, int threshold, Instant occurredAt) {
}
