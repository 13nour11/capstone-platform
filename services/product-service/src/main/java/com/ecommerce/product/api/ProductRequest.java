package com.ecommerce.product.api;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.ecommerce.product.application.ProductCommand;

/** Body of POST and PUT /api/v1/products. */
public record ProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull @Positive Long categoryId) {

    ProductCommand toCommand() {
        return new ProductCommand(name.strip(), description, price, categoryId);
    }
}
