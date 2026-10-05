package com.ecommerce.order.infrastructure.client;

import com.ecommerce.order.domain.exception.ServiceUnavailableException;
import com.ecommerce.order.infrastructure.client.dto.CheckStockResponse;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The synchronous stock check (FR-06) with the four Resilience4j guards the brief asks for:
 * Retry(CircuitBreaker(TimeLimiter(Bulkhead(call)))). It lives in its own bean so the annotations always run
 * through the Spring proxy, and it answers "is it in stock?" as a value: only a broken call is a failure, so an
 * out-of-stock answer never opens the circuit or gets retried.
 */
@Component
public class InventoryGateway {

    static final String INSTANCE = "inventoryService";

    private static final Logger log = LoggerFactory.getLogger(InventoryGateway.class);

    private final InventoryClient inventoryClient;
    private final Executor executor;

    public InventoryGateway(InventoryClient inventoryClient, @Qualifier("inventoryCallExecutor") Executor executor) {
        this.inventoryClient = inventoryClient;
        this.executor = executor;
    }

    @Retry(name = INSTANCE)
    @CircuitBreaker(name = INSTANCE, fallbackMethod = "stockCheckUnavailable")
    @TimeLimiter(name = INSTANCE)
    @Bulkhead(name = INSTANCE)
    public CompletableFuture<Boolean> isInStock(Long productId, int quantity) {
        return CompletableFuture.supplyAsync(() -> {
            log.info("Calling inventory-service for productId: {}, quantity: {}", productId, quantity);
            CheckStockResponse response = inventoryClient.checkStock(productId, quantity);
            return response != null && response.available();
        }, executor);
    }

    /** FR-06 / failure scenarios 2 and 3: the order is not accepted; the customer gets 503, never "out of stock". */
    public CompletableFuture<Boolean> stockCheckUnavailable(Long productId, int quantity, Throwable cause) {
        log.error("Stock check failed for productId: {} ({}); answering 503", productId, cause.toString());
        return CompletableFuture.failedFuture(new ServiceUnavailableException("Stock check unavailable, please retry"));
    }
}
