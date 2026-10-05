package com.ecommerce.product.infrastructure.kafka;

import com.ecommerce.product.application.RecordRatingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Bonus B1: consumes {@code review-events} (routed on the {@code eventType} header, like every Saga topic). */
@Component
public class ReviewEventsListener {

    static final String TOPIC = "review-events";
    static final String REVIEW_SUBMITTED = "ReviewSubmitted";

    private static final Logger log = LoggerFactory.getLogger(ReviewEventsListener.class);

    private final RecordRatingService ratings;
    private final ObjectMapper objectMapper;

    public ReviewEventsListener(RecordRatingService ratings, ObjectMapper objectMapper) {
        this.ratings = ratings;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC, groupId = "${spring.application.name}")
    public void onReviewEvent(ConsumerRecord<String, String> record) {
        if (!REVIEW_SUBMITTED.equals(eventType(record))) {
            return;
        }
        JsonNode event = parse(record.value());
        String reviewId = text(event, "reviewId");
        long productId = number(event, "productId");
        int rating = (int) number(event, "rating");
        if (rating < 1 || rating > 5) {
            throw new InvalidEventException("ReviewSubmitted rating out of range: " + record.value());
        }
        boolean added = ratings.record(reviewId, productId, rating);
        log.info("ReviewSubmitted reviewId={} productId={} applied={}", reviewId, productId, added);
    }

    private JsonNode parse(String value) {
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new InvalidEventException("ReviewSubmitted is not a JSON object");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Unreadable ReviewSubmitted payload", e);
        }
    }

    private static String text(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new InvalidEventException("ReviewSubmitted is missing '" + field + "'");
        }
        return value.asText();
    }

    private static long number(JsonNode event, String field) {
        JsonNode value = event.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new InvalidEventException("ReviewSubmitted is missing a numeric '" + field + "'");
        }
        return value.asLong();
    }

    private static String eventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("eventType");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
