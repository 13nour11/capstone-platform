package com.ecommerce.gateway.filter;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

    /** One token bucket per signed-in user, otherwise per client IP (FR-13). */
    @Bean
    KeyResolver userOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(auth -> "user:" + auth.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange.getRequest().getRemoteAddress())));
    }

    private static String clientIp(InetSocketAddress remote) {
        return remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "unknown";
    }
}
