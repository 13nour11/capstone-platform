package com.ecommerce.order.api.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
    Long id,
    Long productId,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal subtotal
) {}
