package com.ecommerce.payment.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/** Deduplicates redelivered events. Must run inside the transaction that applies the event. */
@Repository
public class ProcessedEventRepository {

    private final JdbcClient jdbcClient;

    public ProcessedEventRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * @return {@code true} the first time this consumer sees the event, {@code false} on a redelivery
     */
    public boolean markProcessed(String eventId, String consumer, Instant now) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO processed_event (event_id, consumer, processed_at)
                        VALUES (:eventId, :consumer, :processedAt)
                        ON CONFLICT (event_id, consumer) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("consumer", consumer)
                .param("processedAt", Timestamp.from(now))
                .update();
        return inserted == 1;
    }
}
