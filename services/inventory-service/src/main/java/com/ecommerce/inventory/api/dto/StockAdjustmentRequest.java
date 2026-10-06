package com.ecommerce.inventory.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A boxed Integer so a missing or misspelled field (e.g. {"quantity":5}) is a 400, not a silent stock of 0.
 */
public record StockAdjustmentRequest(
    @NotNull(message = "availableQuantity is required")
    @PositiveOrZero(message = "Quantity must be zero or positive")
    Integer availableQuantity
) {}
