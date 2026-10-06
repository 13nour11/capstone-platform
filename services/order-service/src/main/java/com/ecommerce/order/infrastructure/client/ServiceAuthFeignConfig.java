package com.ecommerce.order.infrastructure.client;

import com.ecommerce.order.infrastructure.security.ServiceTokenProvider;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

/**
 * Feign configuration for calls the order-service makes on its own behalf (FR-14). Deliberately not
 * a {@code @Configuration}: it applies only to the clients that name it, never to every Feign client.
 * A failure to obtain the token surfaces as a failed call, so Retry and the circuit breaker handle it.
 */
public class ServiceAuthFeignConfig {

    @Bean
    RequestInterceptor serviceTokenInterceptor(ServiceTokenProvider tokens) {
        return template -> tokens.serviceToken()
                .ifPresent(token -> template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
