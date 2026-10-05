package com.ecommerce.order.analytics.infrastructure;

import com.ecommerce.order.analytics.domain.AnalyticsOrderStatus;
import com.ecommerce.order.analytics.domain.HourlyOrderStats;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Idempotent writes to the analytics read model; every statement is a single atomic upsert. */
@Repository
public class AnalyticsRepository {

    private final JdbcClient jdbcClient;

    public AnalyticsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** @return {@code true} the first time the event is seen, {@code false} on a redelivery */
    public boolean markProcessed(String eventId, Instant now) {
        return jdbcClient.sql("""
                        INSERT INTO analytics_processed_event (event_id, processed_at)
                        VALUES (:eventId, :now)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("now", Timestamp.from(now))
                .update() == 1;
    }

    /**
     * Records the amount and time of a placed order without overwriting a final status that
     * arrived first.
     *
     * @return the order's status after the write
     */
    public AnalyticsOrderStatus recordPlaced(String orderId, BigDecimal totalAmount, Instant placedAt, Instant now) {
        String status = jdbcClient.sql("""
                        INSERT INTO analytics_order (order_id, status, total_amount, placed_at, updated_at)
                        VALUES (:orderId, 'PENDING', :totalAmount, :placedAt, :now)
                        ON CONFLICT (order_id) DO UPDATE
                            SET total_amount = EXCLUDED.total_amount,
                                placed_at = EXCLUDED.placed_at,
                                updated_at = EXCLUDED.updated_at
                        RETURNING status
                        """)
                .param("orderId", orderId)
                .param("totalAmount", totalAmount)
                .param("placedAt", Timestamp.from(placedAt))
                .param("now", Timestamp.from(now))
                .query(String.class)
                .single();
        return AnalyticsOrderStatus.valueOf(status);
    }

    /**
     * Moves a pending (or not yet seen) order to its final status.
     *
     * @return the closed order if this call changed its status; empty if it was already final
     */
    public Optional<ClosedOrder> close(String orderId, AnalyticsOrderStatus finalStatus, Instant now) {
        return jdbcClient.sql("""
                        INSERT INTO analytics_order (order_id, status, updated_at)
                        VALUES (:orderId, :status, :now)
                        ON CONFLICT (order_id) DO UPDATE
                            SET status = EXCLUDED.status,
                                updated_at = EXCLUDED.updated_at
                            WHERE analytics_order.status = 'PENDING'
                        RETURNING total_amount
                        """)
                .param("orderId", orderId)
                .param("status", finalStatus.name())
                .param("now", Timestamp.from(now))
                .query((rs, row) -> new ClosedOrder(rs.getBigDecimal("total_amount")))
                .optional();
    }

    /** @param totalAmount {@code null} while the order's placed event has not arrived */
    public record ClosedOrder(BigDecimal totalAmount) {
    }

    public List<HourlyOrderStats> hourlyStats(Instant from, Instant to) {
        return jdbcClient.sql("""
                        SELECT date_trunc('hour', placed_at) AS hour,
                               count(*) AS placed,
                               count(*) FILTER (WHERE status = 'CONFIRMED') AS confirmed,
                               count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                               coalesce(sum(total_amount) FILTER (WHERE status = 'CONFIRMED'), 0) AS revenue
                        FROM analytics_order
                        WHERE placed_at >= :from AND placed_at < :to
                        GROUP BY 1
                        ORDER BY 1
                        """)
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, row) -> new HourlyOrderStats(
                        rs.getTimestamp("hour").toInstant(),
                        rs.getLong("placed"),
                        rs.getLong("confirmed"),
                        rs.getLong("cancelled"),
                        rs.getBigDecimal("revenue")))
                .list();
    }
}
