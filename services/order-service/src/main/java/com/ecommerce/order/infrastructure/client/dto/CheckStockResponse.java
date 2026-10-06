package com.ecommerce.order.infrastructure.client.dto;

public record CheckStockResponse(
    Long productId,
    int requestedQuantity,
    boolean available
) {}
