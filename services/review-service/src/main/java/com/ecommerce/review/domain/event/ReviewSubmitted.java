package com.ecommerce.review.domain.event;

import java.time.Instant;

/** Published to {@code review-events}, key = productId. product-service folds it into the product's rating. */
public record ReviewSubmitted(String eventId, String reviewId, long productId, int rating, Instant occurredAt) {

    public static final String TOPIC = "review-events";
}
