package com.ecommerce.inventory.domain.event;

import java.time.Instant;

public record InventoryReservationFailed(
    String eventId,
    String orderId,
    String reason,
    Instant occurredAt
) {}
