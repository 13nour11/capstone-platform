package com.ecommerce.product.application;

import com.ecommerce.product.infrastructure.CacheConfig;
import com.ecommerce.product.infrastructure.ProductRatingRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bonus B1: folds one ReviewSubmitted into the product's rating summary. The average and count are computed
 * from the stored ratings at read time, so duplicates and reordering can never make them drift.
 */
@Service
public class RecordRatingService {

    private final ProductRatingRepository ratings;

    public RecordRatingService(ProductRatingRepository ratings) {
        this.ratings = ratings;
    }

    /** @return true when the rating was new (false: a redelivered event, nothing changed) */
    @Transactional
    @Caching(evict = {
            // The rating event does not say which tenant owns the product, so every cached entry goes
            @CacheEvict(cacheNames = CacheConfig.PRODUCT, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS, allEntries = true)
    })
    public boolean record(String reviewId, long productId, int rating) {
        return ratings.insertIfAbsent(reviewId, productId, rating) == 1;
    }
}
