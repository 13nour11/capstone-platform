package com.ecommerce.order.analytics.api;

import com.ecommerce.order.analytics.application.AnalyticsQueryService;
import com.ecommerce.order.analytics.domain.AnalyticsSummary;
import com.ecommerce.order.analytics.domain.HourlyOrderStats;
import com.ecommerce.order.infrastructure.security.KeycloakRealmRoleConverter;
import com.ecommerce.order.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyticsController.class)
@Import(SecurityConfig.class)
class AnalyticsControllerTest {

    private static final Instant HOUR = Instant.parse("2026-10-04T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsQueryService queryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldReturn401_whenTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn403_whenCallerIsNotAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/summary").with(role("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnSummaryForLast24Hours_byDefault() throws Exception {
        when(queryService.summary(Duration.ofHours(24))).thenReturn(AnalyticsSummary.of(HOUR.minusSeconds(86_400),
                HOUR, List.of(new HourlyOrderStats(HOUR, 4, 2, 1, new BigDecimal("30.00")))));

        mockMvc.perform(get("/api/v1/analytics/summary").with(role("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").value(4))
                .andExpect(jsonPath("$.ordersByStatus.pending").value(1))
                .andExpect(jsonPath("$.ordersByStatus.confirmed").value(2))
                .andExpect(jsonPath("$.ordersByStatus.cancelled").value(1))
                .andExpect(jsonPath("$.revenue").value(30.00))
                .andExpect(jsonPath("$.cancelledRatio").value(0.25))
                .andExpect(jsonPath("$.hourly[0].placed").value(4));
    }

    @Test
    void shouldReturn400_whenWindowIsOutOfRange() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/summary").param("hours", "0").with(role("ADMIN")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/analytics/summary").param("hours", "169").with(role("ADMIN")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(queryService);
    }

    /** A Keycloak-shaped token; the role converter under test reads realm_access.roles. */
    private static JwtRequestPostProcessor role(String role) {
        return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
