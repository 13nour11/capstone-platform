package com.ecommerce.product.infrastructure.kafka;

import com.ecommerce.product.application.RecordRatingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReviewEventsListenerTest {

    private final RecordRatingService ratings = mock(RecordRatingService.class);
    private final ReviewEventsListener listener = new ReviewEventsListener(ratings, new ObjectMapper());

    @Test
    void shouldRecordRating_whenReviewSubmittedArrives() {
        listener.onReviewEvent(record("ReviewSubmitted",
                "{\"eventId\":\"e-1\",\"reviewId\":\"r-1\",\"productId\":7,\"rating\":4}"));

        verify(ratings).record("r-1", 7L, 4);
    }

    @Test
    void shouldIgnoreOtherEventTypes() {
        listener.onReviewEvent(record("ReviewDeleted", "{}"));

        verify(ratings, never()).record(anyString(), anyLong(), anyInt());
    }

    @Test
    void shouldRejectAsPoison_whenRatingIsOutOfRange() {
        assertThatThrownBy(() -> listener.onReviewEvent(record("ReviewSubmitted",
                "{\"reviewId\":\"r-1\",\"productId\":7,\"rating\":9}")))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void shouldRejectAsPoison_whenPayloadIsNotJson() {
        assertThatThrownBy(() -> listener.onReviewEvent(record("ReviewSubmitted", "not-json")))
                .isInstanceOf(InvalidEventException.class);
    }

    private static ConsumerRecord<String, String> record(String eventType, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("review-events", 0, 0L, "7", value);
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
