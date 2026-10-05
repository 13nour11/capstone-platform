package com.ecommerce.order.infrastructure.client;

import feign.RequestInterceptor;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
public class InventoryClientConfig {

    /** FR-14: every Feign call carries order-service's own client-credentials token. */
    @Bean
    RequestInterceptor serviceTokenInterceptor(ServiceTokenProvider tokens) {
        return template -> tokens.accessToken()
                .ifPresent(token -> template.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /**
     * Threads for the time-limited stock check. The trace context travels with each task (NFR-06), so the Feign
     * call stays inside the customer's trace.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService inventoryCallExecutor(@Value("${order.inventory-call.threads:20}") int threads) {
        return ContextExecutorService.wrap(Executors.newFixedThreadPool(threads),
                () -> ContextSnapshotFactory.builder().build().captureAll());
    }
}
