package com.ecommerce.inventory.api;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.api.dto.StockAdjustmentRequest;
import com.ecommerce.inventory.api.dto.StockResponse;
import com.ecommerce.inventory.application.InventoryService;
import com.ecommerce.inventory.infrastructure.security.KeycloakRealmRoleConverter;
import com.ecommerce.inventory.infrastructure.security.SecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InventoryController.class)
@Import(SecurityConfig.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InventoryService inventoryService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("GET /api/v1/inventory/check should return 200 with availability status")
    void shouldReturn200AndAvailable_whenStockCheckSucceeds() throws Exception {
        when(inventoryService.checkStock(1L, 5))
                .thenReturn(new CheckStockResponse(1L, 5, true));

        mockMvc.perform(get("/api/v1/inventory/check").with(role("SERVICE"))
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
        mockMvc.perform(get("/api/v1/inventory/check").with(role("SERVICE"))
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

        mockMvc.perform(get("/api/v1/inventory/1").with(role("ADMIN"))
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

        mockMvc.perform(put("/api/v1/inventory/1").with(role("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.available").value(75));
    }

    @Test
    @DisplayName("PUT /api/v1/inventory/{id} with a misspelled field is 400, never a silent stock of 0")
    void shouldReturn400_whenAvailableQuantityIsMissing() throws Exception {
        mockMvc.perform(put("/api/v1/inventory/1").with(role("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":1000000}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("GET /api/v1/inventory/{id} should return 404 RFC 7807 ProblemDetail when product not found")
    void shouldReturn404ProblemDetail_whenProductNotFound() throws Exception {
        when(inventoryService.getStock(999L))
                .thenThrow(new com.ecommerce.inventory.domain.exception.ProductNotFoundException(999L));

        mockMvc.perform(get("/api/v1/inventory/999").with(role("ADMIN"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("Product Not Found"));
    }

    @Test
    @DisplayName("Security: the stock check needs a token")
    void shouldReturn401_whenCheckHasNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/check").param("productId", "1").param("quantity", "5"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Security: a customer or admin token cannot call the internal stock check (SERVICE only)")
    void shouldReturn403_whenCheckIsCalledWithoutServiceRole() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/check").param("productId", "1").param("quantity", "5")
                        .with(role("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security: a customer cannot adjust stock (FR-12 is ADMIN)")
    void shouldReturn403_whenCustomerAdjustsStock() throws Exception {
        mockMvc.perform(put("/api/v1/inventory/1").with(role("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"availableQuantity\":5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/inventory/low-stock lists products below the threshold (ADMIN)")
    void shouldReturnLowStock_whenAdminAsks() throws Exception {
        when(inventoryService.getLowStock()).thenReturn(java.util.List.of(new StockResponse(4L, 0, 0)));

        mockMvc.perform(get("/api/v1/inventory/low-stock").with(role("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(4));
    }

    private static JwtRequestPostProcessor role(String role) {
        return jwt().jwt(jwt -> jwt.claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
