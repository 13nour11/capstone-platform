package com.ecommerce.order.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** Only the fields order-service prices with; the catalogue owns the rest. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductPriceResponse(Long id, BigDecimal price) {
}
