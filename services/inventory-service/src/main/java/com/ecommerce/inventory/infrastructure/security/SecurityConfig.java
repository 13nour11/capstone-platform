package com.ecommerce.inventory.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Defence in depth (ADD D7.1): the gateway already denies {@code /check} and requires ADMIN for the
 * stock endpoints, but inventory is reachable inside the cluster, so it checks the token itself.
 * <p>
 * {@code /check} is the order-service's call (FR-14: client credentials, realm role SERVICE). It is
 * enforced once {@code inventory.security.require-service-token} is on, which must happen together
 * with order-service sending the token ({@code order.service-auth.enabled}); otherwise every order
 * would fail its stock check.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Value("${inventory.security.require-service-token:false}") boolean requireServiceToken) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll();
                    if (requireServiceToken) {
                        auth.requestMatchers(HttpMethod.GET, "/api/v1/inventory/check").hasAnyRole("SERVICE", "ADMIN");
                    } else {
                        auth.requestMatchers(HttpMethod.GET, "/api/v1/inventory/check").permitAll();
                    }
                    auth.requestMatchers("/api/v1/inventory/**").hasRole("ADMIN");
                    auth.anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter)))
                .build();
    }
}
