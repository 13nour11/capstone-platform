package com.ecommerce.inventory.domain.event;

import java.math.BigDecimal;

public record OrderItemPayload(
    Long productId,
    int quantity,
    BigDecimal unitPrice
) {}
