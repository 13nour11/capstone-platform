package com.ecommerce.review.application;

import com.ecommerce.review.domain.Review;

import java.time.Instant;

public record ReviewView(String reviewId, long productId, String customerId, int rating, String comment,
                         Instant createdAt) {

    static ReviewView from(Review review) {
        return new ReviewView(review.getId().toString(), review.getProductId(), review.getCustomerId(),
                review.getRating(), review.getComment(), review.getCreatedAt());
    }
}
