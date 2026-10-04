package com.ecommerce.inventory.domain;

import com.ecommerce.inventory.domain.exception.InsufficientStockException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockDomainTest {

    @Test
    @DisplayName("Should successfully reserve stock and update available and reserved counts")
    void shouldReserveStock_whenStockIsAvailable() {
        Stock stock = Stock.builder()
                .productId(1L)
                .available(10)
                .reserved(0)
                .version(0L)
                .build();

        stock.reserve(4);

        assertThat(stock.getAvailable()).isEqualTo(6);
        assertThat(stock.getReserved()).isEqualTo(4);
    }

    @Test
    @DisplayName("Should throw InsufficientStockException when reserving more than available")
    void shouldThrowInsufficientStockException_whenStockIsInsufficient() {
        Stock stock = Stock.builder()
                .productId(1L)
                .available(5)
                .reserved(0)
                .version(0L)
                .build();

        assertThatThrownBy(() -> stock.reserve(6))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Product 1 has 5 units available, but 6 were requested");
    }

    @Test
    @DisplayName("Should release reserved stock and restore available quantity")
    void shouldReleaseStock_whenReleaseCalled() {
        Stock stock = Stock.builder()
                .productId(1L)
                .available(6)
                .reserved(4)
                .version(0L)
                .build();

        stock.release(3);

        assertThat(stock.getAvailable()).isEqualTo(9);
        assertThat(stock.getReserved()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when adjusting available to negative value")
    void shouldThrowException_whenAdjustingToNegativeQuantity() {
        Stock stock = Stock.builder()
                .productId(1L)
                .available(10)
                .reserved(0)
                .version(0L)
                .build();

        assertThatThrownBy(() -> stock.adjustAvailable(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Available quantity cannot be negative");
    }
}
