package com.ecommerce.payment.api;

import com.ecommerce.payment.infrastructure.security.KeycloakRealmRoleConverter;
import com.ecommerce.payment.support.PostgresTestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FR-08 end to end: HTTP → idempotency key → payment row, on a real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
class PaymentIdempotencyIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    void shouldChargeOnceAndReplaySameResponse_whenSameKeyIsSentTwice() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        MvcResult first = mockMvc.perform(payment(key, orderId, "49.90"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(PaymentController.IDEMPOTENT_REPLAYED))
                .andReturn();
        MvcResult second = mockMvc.perform(payment(key, orderId, "49.9"))
                .andExpect(status().isCreated())
                .andExpect(header().string(PaymentController.IDEMPOTENT_REPLAYED, "true"))
                .andReturn();

        assertThat(second.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
        assertThat(paymentsFor(orderId)).isEqualTo(1);
    }

    @Test
    void shouldReturn422_whenKeyIsReusedForDifferentRequest() throws Exception {
        String key = UUID.randomUUID().toString();
        mockMvc.perform(payment(key, "order-" + UUID.randomUUID(), "10.00")).andExpect(status().isCreated());

        mockMvc.perform(payment(key, "order-" + UUID.randomUUID(), "10.00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldReturnExistingPayment_whenOrderIsPaidAgainWithNewKey() throws Exception {
        String orderId = "order-" + UUID.randomUUID();
        String firstPaymentId = jsonPathOf(mockMvc.perform(payment(UUID.randomUUID().toString(), orderId, "25.00"))
                .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(payment(UUID.randomUUID().toString(), orderId, "25.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(firstPaymentId));

        assertThat(paymentsFor(orderId)).isEqualTo(1);
    }

    @Test
    void shouldRefundOnce_whenAdminRetriesRefund() throws Exception {
        String paymentId = jsonPathOf(mockMvc.perform(payment(UUID.randomUUID().toString(),
                "order-" + UUID.randomUUID(), "30.00")).andExpect(status().isCreated()).andReturn());

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/payments/{id}/refund", paymentId).with(role("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("REFUNDED"));
        }
    }

    private MockHttpServletRequestBuilder payment(String key, String orderId, String amount) {
        return post("/api/v1/payments")
                .header(PaymentController.IDEMPOTENCY_KEY, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderId\":\"%s\",\"amount\":%s}".formatted(orderId, amount))
                .with(role("SERVICE"));
    }

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private long paymentsFor(String orderId) {
        return jdbcClient.sql("SELECT count(*) FROM payments WHERE order_id = ?")
                .param(orderId).query(Long.class).single();
    }

    private static String jsonPathOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.paymentId");
    }
}
