package com.ecommerce.payment.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHashTest {

    @Test
    void shouldHashEqually_whenAmountsDifferOnlyInScale() {
        assertThat(RequestHash.of("order-1", new BigDecimal("10.0")))
                .isEqualTo(RequestHash.of("order-1", new BigDecimal("10.00")));
    }

    @Test
    void shouldHashDifferently_whenOrderOrAmountDiffers() {
        String base = RequestHash.of("order-1", new BigDecimal("10.00"));

        assertThat(RequestHash.of("order-2", new BigDecimal("10.00"))).isNotEqualTo(base);
        assertThat(RequestHash.of("order-1", new BigDecimal("10.01"))).isNotEqualTo(base);
        assertThat(base).hasSize(64);
    }
}
