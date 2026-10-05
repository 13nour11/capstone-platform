package com.ecommerce.inventory.domain.event;

import java.time.Instant;

/** Bonus B4: published to inventory-alerts when a product drops below the low-stock threshold. */
public record LowStock(
    String eventId,
    Long productId,
    int available,
    int threshold,
    Instant occurredAt
) {}
