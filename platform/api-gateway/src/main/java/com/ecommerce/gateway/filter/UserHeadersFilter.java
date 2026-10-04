package com.ecommerce.gateway.filter;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Removes every client-sent {@code X-User-*} header (never trusted), then adds the identity taken from the
 * validated JWT: {@code X-User-Id} (sub) and {@code X-User-Roles} (comma-separated realm roles).
 */
@Component
public class UserHeadersFilter implements GlobalFilter, Ordered {

    private static final String USER_ID = "X-User-Id";
    private static final String USER_ROLES = "X-User-Roles";
    private static final String USER_HEADER_PREFIX = "x-user-";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .ofType(JwtAuthenticationToken.class)
                .map(auth -> withUserHeaders(exchange, auth))
                .switchIfEmpty(Mono.fromSupplier(() -> withUserHeaders(exchange, null)))
                .flatMap(chain::filter);
    }

    private static ServerWebExchange withUserHeaders(ServerWebExchange exchange, JwtAuthenticationToken auth) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    removeUserHeaders(headers);
                    if (auth != null) {
                        headers.set(USER_ID, auth.getToken().getSubject());
                        headers.set(USER_ROLES, roles(auth));
                    }
                }))
                .build();
    }

    private static void removeUserHeaders(HttpHeaders headers) {
        List<String> spoofed = headers.keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(USER_HEADER_PREFIX))
                .toList();
        spoofed.forEach(headers::remove);
    }

    private static String roles(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.joining(","));
    }

    @Override
    public int getOrder() {
        return 0;
    }
}
