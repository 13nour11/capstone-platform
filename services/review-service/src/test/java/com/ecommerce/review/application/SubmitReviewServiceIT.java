package com.ecommerce.review.application;

import com.ecommerce.review.domain.DuplicateReviewException;
import com.ecommerce.review.support.PostgresTestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** B1 on a real PostgreSQL (Flyway V1): one review per (product, customer), and the event is in the outbox. */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfig.class)
class SubmitReviewServiceIT {

    @Autowired
    private SubmitReviewService submitReviews;

    @Autowired
    private ReviewQueryService queries;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void shouldStoreReviewAndOutboxEventInOneTransaction() {
        long productId = ThreadLocalRandom.current().nextLong(1_000, 1_000_000);

        ReviewView review = submitReviews.submit(productId, "user-1", 4, "Solid");

        assertThat(queries.forProduct(productId, PageRequest.of(0, 20)).getContent())
                .extracting(ReviewView::reviewId).containsExactly(review.reviewId());
        assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id = ? and event_type = 'ReviewSubmitted'"
                        + " and topic = 'review-events' and published_at is null")
                .param(String.valueOf(productId)).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void shouldRejectSecondReview_andWriteNoSecondEvent() {
        long productId = ThreadLocalRandom.current().nextLong(1_000, 1_000_000);
        submitReviews.submit(productId, "user-2", 5, "First");

        assertThatThrownBy(() -> submitReviews.submit(productId, "user-2", 1, "Second"))
                .isInstanceOf(DuplicateReviewException.class);

        assertThat(jdbc.sql("select count(*) from reviews where product_id = ?").param(productId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id = ?")
                .param(String.valueOf(productId)).query(Long.class).single()).isEqualTo(1);
    }
}
