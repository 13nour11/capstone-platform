package com.ecommerce.order.infrastructure.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placing an order costs two serial remote calls: the stock check, which must be fresh, and the
 * price lookup, which need not be. Holding the price for a short window takes one call off the
 * critical path; the k6 load run showed it was the larger of the two avoidable costs.
 *
 * <p>Deliberately a separate bean from {@link ProductServiceClient}: calling the resilient method
 * from inside the same bean would bypass the Spring proxy and silently drop the circuit breaker,
 * and counting cache hits as calls would skew the breaker's failure rate.
 *
 * <p>Trade-off: a price change takes up to the TTL to reach new orders. Orders already placed keep
 * the price they were quoted, because the amount is stored on the order.
 */
@Component
public class CachedProductPrices {

    private final ProductServiceClient productServiceClient;
    private final Map<Long, CachedPrice> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    public CachedProductPrices(ProductServiceClient productServiceClient,
                               @Value("${product.price-cache-ttl-seconds:60}") long ttlSeconds) {
        this.productServiceClient = productServiceClient;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public BigDecimal currentPrice(Long productId) {
        // A zero or negative TTL is the kill switch: always ask product-service and keep nothing.
        // Without this an entry written and read in the same instant would still count as fresh.
        if (ttl.isZero() || ttl.isNegative()) {
            return productServiceClient.currentPrice(productId);
        }
        CachedPrice cached = cache.get(productId);
        if (cached != null && !cached.isOlderThan(ttl)) {
            return cached.price();
        }
        BigDecimal fresh = productServiceClient.currentPrice(productId);
        cache.put(productId, new CachedPrice(fresh, Instant.now()));
        return fresh;
    }

    private record CachedPrice(BigDecimal price, Instant fetchedAt) {
        boolean isOlderThan(Duration ttl) {
            return Instant.now().isAfter(fetchedAt.plus(ttl));
        }
    }
}
