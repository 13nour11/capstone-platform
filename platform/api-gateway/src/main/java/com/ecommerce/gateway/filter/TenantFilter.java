package com.ecommerce.gateway.filter;

import com.ecommerce.gateway.security.ProblemResponseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * Bonus B3: resolves the tenant of every request and forwards it as {@code X-Tenant-Id}.
 * <ul>
 *   <li>Signed in: the token's {@code tenant_id} claim wins. A different {@code X-Tenant-Id} from the client is 403.</li>
 *   <li>Anonymous (public catalogue): a whitelisted {@code X-Tenant-Id} header, otherwise the default tenant.</li>
 *   <li>An unknown tenant is 400.</li>
 * </ul>
 * Runs before the route filters, so the rate limiter can key its buckets per tenant.
 */
@Component
public class TenantFilter implements GlobalFilter, Ordered {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    static final String TENANT_CLAIM = "tenant_id";

    private final List<String> tenants;
    private final String defaultTenant;
    private final ProblemResponseWriter problems;

    public TenantFilter(@Value("${gateway.tenants.allowed:tenant-a,tenant-b}") List<String> tenants,
                        @Value("${gateway.tenants.default:tenant-a}") String defaultTenant,
                        ProblemResponseWriter problems) {
        this.tenants = tenants;
        this.defaultTenant = defaultTenant;
        this.problems = problems;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        Optional<String> requested = Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(TENANT_HEADER))
                .filter(value -> !value.isBlank());
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(auth -> Optional.ofNullable(auth.getToken().getClaimAsString(TENANT_CLAIM)))
                .defaultIfEmpty(Optional.empty())
                .flatMap(claim -> {
                    String tenant = claim.orElseGet(() -> requested.orElse(defaultTenant));
                    if (claim.isPresent() && requested.isPresent() && !claim.get().equals(requested.get())) {
                        return problems.write(exchange, HttpStatus.FORBIDDEN, "TENANT_MISMATCH",
                                "X-Tenant-Id does not match the tenant of your token");
                    }
                    if (!tenants.contains(tenant)) {
                        return problems.write(exchange, HttpStatus.BAD_REQUEST, "UNKNOWN_TENANT",
                                "Unknown tenant " + tenant);
                    }
                    return chain.filter(exchange.mutate()
                            .request(request -> request.headers(headers -> headers.set(TENANT_HEADER, tenant)))
                            .build());
                });
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
