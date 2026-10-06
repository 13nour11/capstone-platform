package com.ecommerce.inventory.api.dto;

public record CheckStockResponse(
    Long productId,
    int requestedQuantity,
    boolean available
) {}
