package com.ecommerce.order.infrastructure.client;

import com.ecommerce.order.domain.exception.OutOfStockException;
import com.ecommerce.order.domain.exception.ServiceUnavailableException;
import com.ecommerce.order.infrastructure.client.dto.CheckStockResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InventoryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceClient.class);
    private final InventoryClient inventoryClient;

    public InventoryServiceClient(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    @CircuitBreaker(name = "inventoryService", fallbackMethod = "stockCheckFallback")
    @Retry(name = "inventoryService", fallbackMethod = "stockCheckFallback")
    public void verifyStockAvailability(Long productId, int quantity) {
        log.info("Calling inventory-service for productId: {}, quantity: {}", productId, quantity);
        CheckStockResponse response = inventoryClient.checkStock(productId, quantity);
        if (response == null || !response.available()) {
            log.warn("Product {} is out of stock for requested quantity {}", productId, quantity);
            throw new OutOfStockException(productId);
        }
    }

    public void stockCheckFallback(Long productId, int quantity, Throwable throwable) {
        if (throwable instanceof OutOfStockException) {
            throw (OutOfStockException) throwable;
        }
        log.error("Circuit breaker triggered or inventory-service call failed for productId: {}. Reason: {}",
                productId, throwable.getMessage());
        throw new ServiceUnavailableException("Stock check unavailable, please retry");
    }
}
