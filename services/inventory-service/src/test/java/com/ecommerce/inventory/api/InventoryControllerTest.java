package com.ecommerce.inventory.api;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockAdjustmentRequest;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.application.InventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InventoryService inventoryService;

    @Test
    @DisplayName("GET /api/v1/inventory/check should return 200 with availability status")
    void shouldReturn200AndAvailable_whenStockCheckSucceeds() throws Exception {
        when(inventoryService.checkStock(1L, 5))
                .thenReturn(new CheckStockResponse(1L, 5, true));

        mockMvc.perform(get("/api/v1/inventory/check")
                        .param("productId", "1")
                        .param("quantity", "5")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.requestedQuantity").value(5))
                .andExpect(jsonPath("$.available").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/inventory/check should return 400 when quantity is zero or negative")
    void shouldReturn400_whenQuantityIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/check")
                        .param("productId", "1")
                        .param("quantity", "0")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/inventory/{id} should return 200 with stock details")
    void shouldReturnStockDetails_whenGetStockCalled() throws Exception {
        when(inventoryService.getStock(1L))
                .thenReturn(new StockResponse(1L, 50, 10));

        mockMvc.perform(get("/api/v1/inventory/1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.available").value(50))
                .andExpect(jsonPath("$.reserved").value(10));
    }

    @Test
    @DisplayName("PUT /api/v1/inventory/{id} should return 200 with updated stock")
    void shouldAdjustStock_whenPutStockCalled() throws Exception {
        StockAdjustmentRequest request = new StockAdjustmentRequest(75);
        when(inventoryService.adjustStock(1L, 75))
                .thenReturn(new StockResponse(1L, 75, 10));

        mockMvc.perform(put("/api/v1/inventory/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.available").value(75));
    }

    @Test
    @DisplayName("GET /api/v1/inventory/{id} should return 404 RFC 7807 ProblemDetail when product not found")
    void shouldReturn404ProblemDetail_whenProductNotFound() throws Exception {
        when(inventoryService.getStock(999L))
                .thenThrow(new com.ecommerce.inventory.domain.exception.ProductNotFoundException(999L));

        mockMvc.perform(get("/api/v1/inventory/999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Product Not Found"));
    }
}
