package com.ecommerce.inventory.api;

import com.ecommerce.inventory.api.dto.CheckStockResponse;
import com.ecommerce.inventory.application.InventoryService;
import com.ecommerce.inventory.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.ecommerce.inventory.api.InventoryControllerTest.withRole;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-14 with enforcement on: only a service (or an admin) may run the internal stock check. */
@WebMvcTest(controllers = InventoryController.class, properties = "inventory.security.require-service-token=true")
@Import(SecurityConfig.class)
class InventoryServiceTokenSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("/check without a token is rejected with 401")
    void shouldReturn401_whenNoToken() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/check").param("productId", "1").param("quantity", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/check with a customer's token is rejected with 403")
    void shouldReturn403_whenCustomerToken() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/check").param("productId", "1").param("quantity", "1")
                        .with(withRole("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/check with the order-service's SERVICE token is answered")
    void shouldReturn200_whenServiceToken() throws Exception {
        when(inventoryService.checkStock(1L, 1)).thenReturn(new CheckStockResponse(1L, 1, true));

        mockMvc.perform(get("/api/v1/inventory/check").param("productId", "1").param("quantity", "1")
                        .with(withRole("SERVICE")))
                .andExpect(status().isOk());
    }
}
