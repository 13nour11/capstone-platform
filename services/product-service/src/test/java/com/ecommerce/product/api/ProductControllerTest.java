package com.ecommerce.product.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ecommerce.product.application.PageResult;
import com.ecommerce.product.application.ProductCommand;
import com.ecommerce.product.application.ProductCommandService;
import com.ecommerce.product.application.ProductDetails;
import com.ecommerce.product.application.ProductQueryService;
import com.ecommerce.product.domain.CategoryNotFoundException;
import com.ecommerce.product.domain.ProductNotFoundException;
import com.ecommerce.product.infrastructure.security.KeycloakRealmRoleConverter;
import com.ecommerce.product.infrastructure.security.SecurityConfig;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, TenantResolver.class})
class ProductControllerTest {

    private static final ProductDetails MOUSE =
            new ProductDetails(1L, "Wireless Mouse", "Ergonomic", new BigDecimal("24.99"), 1L, "Electronics", 4.0, 3L);
    private static final String VALID_BODY = """
            {"name": "Wireless Mouse", "description": "Ergonomic", "price": 24.99, "categoryId": 1}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProductQueryService queries;

    @MockitoBean
    private ProductCommandService commands;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void shouldReturnPage_whenAnonymousListsProducts() throws Exception {
        given(queries.list("tenant-a", PageRequest.of(0, 20, Sort.by("id"))))
                .willReturn(new PageResult<>(List.of(MOUSE), 0, 20, 1, 1));

        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].categoryName").value("Electronics"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void shouldPassWhitelistedSort_whenSortIsGiven() throws Exception {
        given(queries.list("tenant-a", PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "price"))))
                .willReturn(new PageResult<>(List.of(), 1, 5, 0, 0));

        mvc.perform(get("/api/v1/products").param("page", "1").param("size", "5").param("sort", "price,desc"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturn400_whenPageSizeAbove50() throws Exception {
        mvc.perform(get("/api/v1/products").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void shouldReturn400_whenSortPropertyIsNotWhitelisted() throws Exception {
        mvc.perform(get("/api/v1/products").param("sort", "version,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
    }

    @Test
    void shouldReturnProductWithCategoryName_whenAnonymousGetsById() throws Exception {
        given(queries.getById("tenant-a", 1L)).willReturn(MOUSE);

        mvc.perform(get("/api/v1/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Wireless Mouse"))
                .andExpect(jsonPath("$.categoryName").value("Electronics"));
    }

    @Test
    void shouldReturn404Problem_whenProductDoesNotExist() throws Exception {
        given(queries.getById("tenant-a", 99L)).willThrow(new ProductNotFoundException(99L));

        mvc.perform(get("/api/v1/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/products/99"));
    }

    @Test
    void shouldReturn401_whenCreatingWithoutToken() throws Exception {
        mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(commands);
    }

    @Test
    void shouldReturn403_whenCustomerCreatesProduct() throws Exception {
        mvc.perform(post("/api/v1/products").with(withRoles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verifyNoInteractions(commands);
    }

    @Test
    void shouldReturn201WithLocation_whenAdminCreatesProduct() throws Exception {
        given(commands.create(eq("tenant-a"), any(ProductCommand.class))).willReturn(MOUSE);

        mvc.perform(post("/api/v1/products").with(withRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/products/1"))
                .andExpect(jsonPath("$.id").value(1));

        verify(commands).create("tenant-a", new ProductCommand("Wireless Mouse", "Ergonomic", new BigDecimal("24.99"), 1L));
    }

    @Test
    void shouldReturn400WithFieldErrors_whenBodyIsInvalid() throws Exception {
        String invalid = """
                {"name": " ", "price": 0, "categoryId": 1}""";

        mvc.perform(post("/api/v1/products").with(withRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(2));

        verifyNoInteractions(commands);
    }

    @Test
    void shouldReturn400_whenCategoryDoesNotExist() throws Exception {
        given(commands.create(eq("tenant-a"), any(ProductCommand.class))).willThrow(new CategoryNotFoundException(1L));

        mvc.perform(post("/api/v1/products").with(withRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("categoryId"));
    }

    @Test
    void shouldReturn400_whenJsonIsMalformed() throws Exception {
        mvc.perform(post("/api/v1/products").with(withRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void shouldReturn200_whenAdminUpdatesProduct() throws Exception {
        given(commands.update(eq("tenant-a"), eq(1L), any(ProductCommand.class))).willReturn(MOUSE);

        mvc.perform(put("/api/v1/products/1").with(withRoles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Wireless Mouse"));
    }

    @Test
    void shouldReturn204_whenAdminDeletesProduct() throws Exception {
        mvc.perform(delete("/api/v1/products/1").with(withRoles("ADMIN")))
                .andExpect(status().isNoContent());

        verify(commands).delete("tenant-a", 1L);
    }

    @Test
    void shouldReturn404_whenAdminDeletesMissingProduct() throws Exception {
        willThrow(new ProductNotFoundException(99L)).given(commands).delete("tenant-a", 99L);

        mvc.perform(delete("/api/v1/products/99").with(withRoles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    // --- Bonus B3: which tenant a request acts for ---

    @Test
    void shouldReadRequestedTenant_whenAnonymousSendsTenantHeader() throws Exception {
        given(queries.getById("tenant-b", 1L)).willReturn(MOUSE);

        mvc.perform(get("/api/v1/products/1").header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldUseTokenTenant_notHeader_whenAdminWrites() throws Exception {
        mvc.perform(delete("/api/v1/products/1").header("X-Tenant-Id", "tenant-a")
                        .with(jwt().jwt(token -> token.claim("tenant_id", "tenant-b")
                                        .claim("realm_access", Map.of("roles", List.of("ADMIN"))))
                                .authorities(new KeycloakRealmRoleConverter())))
                .andExpect(status().isNoContent());

        verify(commands).delete("tenant-b", 1L);
    }

    private static JwtRequestPostProcessor withRoles(String... roles) {
        return jwt().jwt(token -> token.claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
