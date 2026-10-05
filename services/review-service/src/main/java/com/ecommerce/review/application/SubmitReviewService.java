package com.ecommerce.review.application;

import com.ecommerce.review.domain.DuplicateReviewException;
import com.ecommerce.review.domain.Review;
import com.ecommerce.review.domain.event.ReviewSubmitted;
import com.ecommerce.review.infrastructure.outbox.OutboxWriter;
import com.ecommerce.review.infrastructure.persistence.ReviewRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.util.UUID;

/**
 * Stores a review and its {@code ReviewSubmitted} event in one transaction (outbox). The unique
 * (product, customer) constraint decides duplicates, never a check-then-insert.
 */
@Service
public class SubmitReviewService {

    private final ReviewRepository reviews;
    private final OutboxWriter outbox;
    private final TransactionOperations transactions;
    private final Clock clock;

    public SubmitReviewService(ReviewRepository reviews, OutboxWriter outbox, TransactionOperations transactions,
                               Clock clock) {
        this.reviews = reviews;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public ReviewView submit(long productId, String customerId, int rating, String comment) {
        try {
            return transactions.execute(status -> {
                Review review = reviews.saveAndFlush(new Review(UUID.randomUUID(), productId, customerId, rating,
                        comment, clock.instant()));
                UUID eventId = UUID.randomUUID();
                outbox.write(eventId, String.valueOf(productId), ReviewSubmitted.TOPIC,
                        new ReviewSubmitted(eventId.toString(), review.getId().toString(), productId, rating,
                                review.getCreatedAt()));
                return ReviewView.from(review);
            });
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateReviewException(productId);
        }
    }
}
