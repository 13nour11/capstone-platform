package com.ecommerce.order.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** The price is not part of the request: product-service prices every item server side. */
public record OrderItemRequest(
    @NotNull(message = "Product ID is required")
    Long productId,

    @Positive(message = "Quantity must be greater than zero")
    int quantity
) {}
