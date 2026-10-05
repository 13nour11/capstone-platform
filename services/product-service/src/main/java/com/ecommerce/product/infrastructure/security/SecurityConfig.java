package com.ecommerce.product.infrastructure.security;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Defence in depth: product-service validates the JWT itself, even behind the gateway. Reads are public;
 * write methods carry {@code @PreAuthorize("hasRole('ADMIN')")}.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint unauthorized = (request, response, ex) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            writeProblem(objectMapper, response, problem(request, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "A valid bearer token is required"));
        };
        AccessDeniedHandler forbidden = (request, response, ex) -> writeProblem(objectMapper, response,
                problem(request, HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "You do not have permission to access this resource"));

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    private static ProblemDetail problem(HttpServletRequest request, HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }

    private static void writeProblem(ObjectMapper objectMapper, HttpServletResponse response, ProblemDetail problem)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
