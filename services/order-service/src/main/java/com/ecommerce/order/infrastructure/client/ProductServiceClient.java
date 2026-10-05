package com.ecommerce.order.infrastructure.client;

import com.ecommerce.order.domain.exception.ServiceUnavailableException;
import com.ecommerce.order.infrastructure.client.dto.ProductPriceResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * The catalogue is the only authority on price. A client-supplied amount is never trusted, so an
 * order cannot be placed while the price is unknown.
 */
@Component
public class ProductServiceClient {

    private static final Logger log = LoggerFactory.getLogger(ProductServiceClient.class);

    private final ProductClient productClient;

    public ProductServiceClient(ProductClient productClient) {
        this.productClient = productClient;
    }

    @CircuitBreaker(name = "productService", fallbackMethod = "priceLookupFallback")
    @Retry(name = "productService", fallbackMethod = "priceLookupFallback")
    public BigDecimal currentPrice(Long productId) {
        ProductPriceResponse product = productClient.getProduct(productId);
        if (product == null || product.price() == null) {
            throw new ServiceUnavailableException("Product price unavailable, please retry");
        }
        return product.price();
    }

    public BigDecimal priceLookupFallback(Long productId, Throwable throwable) {
        log.error("Price lookup failed for productId {}: {}", productId, throwable.getMessage());
        throw new ServiceUnavailableException("Product price unavailable, please retry");
    }
}
