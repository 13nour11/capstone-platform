package com.ecommerce.product.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Bonus B3: the tenant of the current request. A signed-in caller's {@code tenant_id} claim always wins;
 * anonymous catalogue reads use the {@code X-Tenant-Id} header that the gateway resolved, else the default.
 */
@Component
public class TenantResolver {

    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String TENANT_CLAIM = "tenant_id";

    private final HttpServletRequest request;
    private final String defaultTenant;

    public TenantResolver(HttpServletRequest request, @Value("${product.default-tenant:tenant-a}") String defaultTenant) {
        this.request = request;
        this.defaultTenant = defaultTenant;
    }

    public String current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString(TENANT_CLAIM);
            if (claim != null && !claim.isBlank()) {
                return claim;
            }
        }
        String header = request.getHeader(TENANT_HEADER);
        return header == null || header.isBlank() ? defaultTenant : header;
    }
}
