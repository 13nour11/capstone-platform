package com.ecommerce.inventory.domain.event;

import java.time.Instant;

/** Compensation done: the stock reserved for this order is available again (Brief event catalogue). */
public record InventoryReleased(
    String eventId,
    String orderId,
    String reason,
    Instant occurredAt
) {}
