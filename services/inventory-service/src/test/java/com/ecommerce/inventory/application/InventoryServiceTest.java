package com.ecommerce.inventory.application;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.domain.Stock;
import com.ecommerce.inventory.domain.exception.ProductNotFoundException;
import com.ecommerce.inventory.infrastructure.persistence.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private StockRepository stockRepository;

    @InjectMocks
    private InventoryServiceImpl inventoryService;

    private Stock testStock;

    @BeforeEach
    void setUp() {
        testStock = Stock.builder()
                .productId(101L)
                .available(20)
                .reserved(5)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("Should return available: true when stock is sufficient")
    void shouldReturnAvailableTrue_whenStockIsSufficient() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));

        CheckStockResponse response = inventoryService.checkStock(101L, 10);

        assertThat(response.productId()).isEqualTo(101L);
        assertThat(response.requestedQuantity()).isEqualTo(10);
        assertThat(response.available()).isTrue();
    }

    @Test
    @DisplayName("Should return available: false when stock is insufficient")
    void shouldReturnAvailableFalse_whenStockIsInsufficient() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));

        CheckStockResponse response = inventoryService.checkStock(101L, 25);

        assertThat(response.productId()).isEqualTo(101L);
        assertThat(response.requestedQuantity()).isEqualTo(25);
        assertThat(response.available()).isFalse();
    }

    @Test
    @DisplayName("Should return available: false when product does not exist")
    void shouldReturnAvailableFalse_whenProductDoesNotExist() {
        when(stockRepository.findById(999L)).thenReturn(Optional.empty());

        CheckStockResponse response = inventoryService.checkStock(999L, 5);

        assertThat(response.productId()).isEqualTo(999L);
        assertThat(response.available()).isFalse();
    }

    @Test
    @DisplayName("Should return stock details when product exists")
    void shouldReturnStockDetails_whenProductExists() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));

        StockResponse response = inventoryService.getStock(101L);

        assertThat(response.productId()).isEqualTo(101L);
        assertThat(response.available()).isEqualTo(20);
        assertThat(response.reserved()).isEqualTo(5);
    }

    @Test
    @DisplayName("Should throw ProductNotFoundException when product not found on getStock")
    void shouldThrowException_whenProductNotFound() {
        when(stockRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.getStock(999L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("Should adjust available stock quantity successfully")
    void shouldAdjustStock_whenValidQuantityGiven() {
        when(stockRepository.findById(101L)).thenReturn(Optional.of(testStock));
        when(stockRepository.save(any(Stock.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockResponse response = inventoryService.adjustStock(101L, 50);

        assertThat(response.available()).isEqualTo(50);
        verify(stockRepository).save(any(Stock.class));
    }
}
