package com.ecommerce.inventory.domain.event;

import java.math.BigDecimal;
import java.time.Instant;

public record InventoryReserved(
    String eventId,
    String orderId,
    String customerId,
    BigDecimal totalAmount,
    Instant occurredAt
) {}
