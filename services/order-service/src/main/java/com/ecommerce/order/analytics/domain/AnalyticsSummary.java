package com.ecommerce.order.analytics.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** Aggregate over orders placed in [from, to). */
public record AnalyticsSummary(Instant from, Instant to, long totalOrders, long pending, long confirmed,
                               long cancelled, BigDecimal revenue, BigDecimal cancelledRatio,
                               List<HourlyOrderStats> hourly) {

    public static AnalyticsSummary of(Instant from, Instant to, List<HourlyOrderStats> hourly) {
        long placed = hourly.stream().mapToLong(HourlyOrderStats::placed).sum();
        long confirmed = hourly.stream().mapToLong(HourlyOrderStats::confirmed).sum();
        long cancelled = hourly.stream().mapToLong(HourlyOrderStats::cancelled).sum();
        BigDecimal revenue = hourly.stream().map(HourlyOrderStats::revenue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cancelledRatio = placed == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(cancelled).divide(BigDecimal.valueOf(placed), 4, RoundingMode.HALF_UP);
        return new AnalyticsSummary(from, to, placed, placed - confirmed - cancelled, confirmed, cancelled,
                revenue, cancelledRatio, List.copyOf(hourly));
    }
}
