package com.ecommerce.order.infrastructure.client;

import com.ecommerce.order.domain.exception.OutOfStockException;
import com.ecommerce.order.domain.exception.ServiceUnavailableException;
import com.ecommerce.order.infrastructure.client.dto.CheckStockResponse;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The synchronous stock pre-check (FR-06). Resilience4j applies, from the outside in:
 * Retry → CircuitBreaker → Bulkhead → the Feign call, whose connect/read timeouts are the time limit
 * (config-repo/order-service.yml). Only the outermost layer has a fallback, so the breaker records
 * the real failure and the retry never re-runs a fallback's exception.
 * An out-of-stock answer is a business result, not a failure: it is neither retried nor counted by
 * the breaker, so a run of sold-out products cannot open the circuit for everyone else.
 */
@Component
public class InventoryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceClient.class);
    private final InventoryClient inventoryClient;

    public InventoryServiceClient(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @Retry(name = "inventoryService", fallbackMethod = "stockCheckFallback")
    @CircuitBreaker(name = "inventoryService")
    @Bulkhead(name = "inventoryService")
    public void verifyStockAvailability(Long productId, int quantity) {
        log.info("Calling inventory-service for productId: {}, quantity: {}", productId, quantity);
        CheckStockResponse response = inventoryClient.checkStock(productId, quantity);
        if (response == null || !response.available()) {
            log.warn("Product {} is out of stock for requested quantity {}", productId, quantity);
            throw new OutOfStockException(productId);
        }
    }

    public void stockCheckFallback(Long productId, int quantity, Throwable throwable) {
        if (throwable instanceof OutOfStockException outOfStock) {
            throw outOfStock;
        }
        log.error("Stock check unavailable for productId {} ({}): {}",
                productId, throwable.getClass().getSimpleName(), throwable.getMessage());
        throw new ServiceUnavailableException("Stock check unavailable, please retry");
    }
}
