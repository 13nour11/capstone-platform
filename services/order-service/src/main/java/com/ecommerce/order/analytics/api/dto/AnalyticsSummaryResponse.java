package com.ecommerce.order.analytics.api.dto;

import com.ecommerce.order.analytics.domain.AnalyticsSummary;
import com.ecommerce.order.analytics.domain.HourlyOrderStats;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AnalyticsSummaryResponse(Instant from, Instant to, long totalOrders, OrdersByStatus ordersByStatus,
                                       BigDecimal revenue, BigDecimal cancelledRatio, List<Hourly> hourly) {

    public record OrdersByStatus(long pending, long confirmed, long cancelled) {
    }

    public record Hourly(Instant hour, long placed, long confirmed, long cancelled, long pending, BigDecimal revenue) {

        static Hourly from(HourlyOrderStats stats) {
            return new Hourly(stats.hour(), stats.placed(), stats.confirmed(), stats.cancelled(), stats.pending(),
                    stats.revenue());
        }
    }

    public static AnalyticsSummaryResponse from(AnalyticsSummary summary) {
        return new AnalyticsSummaryResponse(summary.from(), summary.to(), summary.totalOrders(),
                new OrdersByStatus(summary.pending(), summary.confirmed(), summary.cancelled()),
                summary.revenue(), summary.cancelledRatio(),
                summary.hourly().stream().map(Hourly::from).toList());
    }
}
