package com.ecommerce.gateway.filter;


import java.net.InetSocketAddress;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    /**
     * One token bucket per signed-in user, otherwise per client IP (FR-13), inside the tenant resolved by
     * {@link TenantFilter} (B3: one tenant's traffic never spends another tenant's budget).
     */
    @Bean
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(auth -> "user:" + auth.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange.getRequest().getRemoteAddress())))
                .map(client -> "tenant:" + tenant(exchange) + ":" + client);
    }

    private static String tenant(ServerWebExchange exchange) {
        String tenant = exchange.getRequest().getHeaders().getFirst(TenantFilter.TENANT_HEADER);
        return tenant == null ? "none" : tenant;
    }

    private static String clientIp(InetSocketAddress remote) {
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
