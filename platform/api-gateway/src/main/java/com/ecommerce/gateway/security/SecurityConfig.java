package com.ecommerce.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/**
 * The gateway is the trust boundary: every JWT is validated here (FR-04) and each path gets a role rule.
 * Anything not listed is denied.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ProblemResponseWriter problems) {
        ServerAuthenticationEntryPoint unauthorized = (exchange, ex) -> {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            return problems.write(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "A valid bearer token is required");
        };
        ServerAccessDeniedHandler forbidden = (exchange, ex) -> problems.write(exchange, HttpStatus.FORBIDDEN,
                "FORBIDDEN", "You do not have permission to access this resource");

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                // Stateless API: the default request cache calls getSession() on every request, which creates a
                // WebSession (SecureRandom id, built on boundedElastic) per call. Under load that contention put
                // ~700 ms in front of every routed request (Performance Report §8.2).
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        // B1: customers review products; reading reviews is public (GET rule above)
                        .pathMatchers(HttpMethod.POST, "/api/v1/products/*/reviews").hasRole("CUSTOMER")
                        .pathMatchers("/api/v1/products/**").hasRole("ADMIN")
                        .pathMatchers("/api/v1/orders/**").hasRole("CUSTOMER")
                        // Internal stock check: only order-service calls it, directly over the service network
                        .pathMatchers("/api/v1/inventory/check/**").denyAll()
                        .pathMatchers("/api/v1/inventory/**", "/api/v1/payments/**", "/api/v1/analytics/**",
                                "/api/v1/alerts/**")
                        .hasRole("ADMIN")
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    private static ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
