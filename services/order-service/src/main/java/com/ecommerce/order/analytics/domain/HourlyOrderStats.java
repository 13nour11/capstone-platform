package com.ecommerce.order.analytics.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Orders placed in one hour and how they ended; revenue counts confirmed orders only. */
public record HourlyOrderStats(Instant hour, long placed, long confirmed, long cancelled, BigDecimal revenue) {

    public long pending() {
        return placed - confirmed - cancelled;
    }
}
