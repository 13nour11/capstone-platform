package com.ecommerce.inventory.api.dto;

import jakarta.validation.constraints.PositiveOrZero;

public record StockAdjustmentRequest(
    @PositiveOrZero(message = "Quantity must be zero or positive")
    int availableQuantity
) {}
