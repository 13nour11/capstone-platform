package com.ecommerce.product.application;

import java.math.BigDecimal;

/** Input of a create or full update, already validated at the API boundary. */
public record ProductCommand(String name, String description, BigDecimal price, long categoryId) {
}
