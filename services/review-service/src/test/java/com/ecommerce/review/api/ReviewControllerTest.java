package com.ecommerce.review.api;

import com.ecommerce.review.application.ReviewQueryService;
import com.ecommerce.review.application.ReviewView;
import com.ecommerce.review.application.SubmitReviewService;
import com.ecommerce.review.domain.DuplicateReviewException;
import com.ecommerce.review.infrastructure.security.KeycloakRealmRoleConverter;
import com.ecommerce.review.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewController.class)
@Import(SecurityConfig.class)
class ReviewControllerTest {

    private static final String BODY = "{\"rating\":5,\"comment\":\"Great mouse\"}";
    private static final ReviewView REVIEW = new ReviewView("r-1", 1L, "user-1", 5, "Great mouse",
            Instant.parse("2026-10-01T10:00:00Z"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubmitReviewService submitReviewService;

    @MockitoBean
    private ReviewQueryService reviewQueryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldReturn201_whenCustomerSubmitsReview() throws Exception {
        when(submitReviewService.submit(1L, "user-1", 5, "Great mouse")).thenReturn(REVIEW);

        mockMvc.perform(post("/api/v1/products/1/reviews").with(user("user-1", "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewId").value("r-1"))
                .andExpect(jsonPath("$.customerId").value("user-1"));
    }

    @Test
    void shouldReturn401_whenSubmittingWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/products/1/reviews").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(submitReviewService);
    }

    @Test
    void shouldReturn403_whenAdminSubmitsReview() throws Exception {
        mockMvc.perform(post("/api/v1/products/1/reviews").with(user("admin-1", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn400_whenRatingIsOutOfRange() throws Exception {
        mockMvc.perform(post("/api/v1/products/1/reviews").with(user("user-1", "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":6,\"comment\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(submitReviewService);
    }

    @Test
    void shouldReturn409_whenCustomerReviewsSameProductTwice() throws Exception {
        when(submitReviewService.submit(anyLong(), anyString(), anyInt(), anyString()))
                .thenThrow(new DuplicateReviewException(1L));

        mockMvc.perform(post("/api/v1/products/1/reviews").with(user("user-1", "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REVIEW"));
    }

    @Test
    void shouldListReviewsPublicly_withPagination() throws Exception {
        when(reviewQueryService.forProduct(eq(1L), any()))
                .thenReturn(new PageImpl<>(List.of(REVIEW), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/products/1/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].rating").value(5))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldReturn400_whenPageSizeAbove50() throws Exception {
        mockMvc.perform(get("/api/v1/products/1/reviews").param("size", "51"))
                .andExpect(status().isBadRequest());
    }

    private static JwtRequestPostProcessor user(String subject, String role) {
        return jwt().jwt(jwt -> jwt.subject(subject).claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
