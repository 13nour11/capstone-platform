package com.ecommerce.inventory.api.dto;

public record StockResponse(
    Long productId,
    int available,
    int reserved
) {}
