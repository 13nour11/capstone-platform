package com.ecommerce.payment.api;

import com.ecommerce.payment.application.PaymentView;
import com.ecommerce.payment.application.ProcessPaymentResult;
import com.ecommerce.payment.application.ProcessPaymentService;
import com.ecommerce.payment.application.RefundPaymentService;
import com.ecommerce.payment.domain.PaymentStatus;
import com.ecommerce.payment.domain.exception.IdempotencyKeyReusedException;
import com.ecommerce.payment.domain.exception.PaymentNotFoundException;
import com.ecommerce.payment.domain.exception.RefundNotAllowedException;
import com.ecommerce.payment.infrastructure.security.KeycloakRealmRoleConverter;
import com.ecommerce.payment.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    private static final String BODY = "{\"orderId\":\"order-1\",\"amount\":49.99}";
    private static final UUID PAYMENT_ID = UUID.fromString("6f1c1e9a-7d0b-4a39-9f3e-2b5c4f0d8a11");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProcessPaymentService processPaymentService;

    @MockitoBean
    private RefundPaymentService refundPaymentService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldReturn401_whenTokenIsMissing() throws Exception {
        mockMvc.perform(paymentRequest("key-1")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn403_whenCallerIsCustomer() throws Exception {
        mockMvc.perform(paymentRequest("key-1").with(role("CUSTOMER"))).andExpect(status().isForbidden());
        verifyNoInteractions(processPaymentService);
    }

    @Test
    void shouldReturn201_whenServiceChargesNewOrder() throws Exception {
        when(processPaymentService.process("key-1", "order-1", new BigDecimal("49.99")))
                .thenReturn(new ProcessPaymentResult(201, view(PaymentStatus.COMPLETED), false));

        mockMvc.perform(paymentRequest("key-1").with(role("SERVICE")))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(PaymentController.IDEMPOTENT_REPLAYED))
                .andExpect(jsonPath("$.paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void shouldMarkReplay_whenKeyWasAlreadyUsed() throws Exception {
        when(processPaymentService.process(eq("key-1"), anyString(), any()))
                .thenReturn(new ProcessPaymentResult(201, view(PaymentStatus.COMPLETED), true));

        mockMvc.perform(paymentRequest("key-1").with(role("ADMIN")))
                .andExpect(status().isCreated())
                .andExpect(header().string(PaymentController.IDEMPOTENT_REPLAYED, "true"));
    }

    @Test
    void shouldReturn400_whenIdempotencyKeyIsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(role("SERVICE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(processPaymentService);
    }

    @Test
    void shouldReturn400_whenAmountIsNotPositive() throws Exception {
        mockMvc.perform(post("/api/v1/payments").header(PaymentController.IDEMPOTENCY_KEY, "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"order-1\",\"amount\":0}")
                        .with(role("SERVICE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn422_whenKeyIsReusedWithDifferentBody() throws Exception {
        when(processPaymentService.process(eq("key-1"), anyString(), any()))
                .thenThrow(new IdempotencyKeyReusedException("key-1"));

        mockMvc.perform(paymentRequest("key-1").with(role("SERVICE")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void shouldReturn403_whenServiceRequestsRefund() throws Exception {
        mockMvc.perform(refundRequest().with(role("SERVICE"))).andExpect(status().isForbidden());
        verifyNoInteractions(refundPaymentService);
    }

    @Test
    void shouldReturn200_whenAdminRefunds() throws Exception {
        when(refundPaymentService.refund(PAYMENT_ID)).thenReturn(view(PaymentStatus.REFUNDED));

        mockMvc.perform(refundRequest().with(role("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void shouldReturn404_whenRefundingUnknownPayment() throws Exception {
        when(refundPaymentService.refund(PAYMENT_ID)).thenThrow(new PaymentNotFoundException(PAYMENT_ID));

        mockMvc.perform(refundRequest().with(role("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    void shouldReturn409_whenRefundingDeclinedPayment() throws Exception {
        when(refundPaymentService.refund(PAYMENT_ID))
                .thenThrow(new RefundNotAllowedException(PAYMENT_ID, PaymentStatus.FAILED));

        mockMvc.perform(refundRequest().with(role("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_ALLOWED"));
    }

    private static MockHttpServletRequestBuilder paymentRequest(String idempotencyKey) {
        return post("/api/v1/payments")
                .header(PaymentController.IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY);
    }

    private static MockHttpServletRequestBuilder refundRequest() {
        return post("/api/v1/payments/{id}/refund", PAYMENT_ID);
    }

    /** A Keycloak-shaped token; the role converter under test reads realm_access.roles. */
    private static JwtRequestPostProcessor role(String role) {
        return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    private static PaymentView view(PaymentStatus status) {
        return new PaymentView(PAYMENT_ID, "order-1", new BigDecimal("49.99"), status, null,
                Instant.parse("2026-10-04T10:00:00Z"));
    }
}
