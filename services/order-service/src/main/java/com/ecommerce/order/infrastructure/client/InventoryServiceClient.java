package com.ecommerce.order.infrastructure.client;

import com.ecommerce.order.domain.exception.OutOfStockException;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletionException;

/** What the order use case calls: waits for the guarded stock check and turns its answer into an outcome. */
@Component
public class InventoryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryServiceClient.class);

    private final InventoryGateway inventoryGateway;

    public InventoryServiceClient(InventoryGateway inventoryGateway) {
        this.inventoryGateway = inventoryGateway;
    }

    /**
     * @throws OutOfStockException when inventory answers that the quantity is not available (409)
     * @throws com.ecommerce.order.domain.exception.ServiceUnavailableException when the check itself failed (503)
     */
    @Retry(name = InventoryGateway.INSTANCE)
    public void verifyStockAvailability(Long productId, int quantity) {
        boolean inStock;
        try {
            inStock = inventoryGateway.isInStock(productId, quantity).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
        if (!inStock) {
            log.warn("Product {} is out of stock for requested quantity {}", productId, quantity);
            throw new OutOfStockException(productId);
        }
    }
}
