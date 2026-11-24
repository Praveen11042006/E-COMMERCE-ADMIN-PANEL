package com.examly.springapp.dto;

import lombok.*;

/**
 * Phase 10C — aggregate order metrics over the FULL order table.
 *
 * <p>The admin order list paginates, so the KPI cards can no longer be derived
 * from the rows on the current page. These numbers are computed server-side
 * across every order, so the cards keep showing the real totals.
 *
 * <p>Revenue and the average order value exclude CANCELLED orders (matching
 * the existing {@code AnalyticsService} convention).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatsDto {

    /** Every order, including cancelled ones. */
    private long total;

    private long pending;

    private long processing;

    private long shipped;

    private long delivered;

    private long cancelled;

    /** Sum of {@code totalAmount} over all non-cancelled orders. */
    private double totalRevenue;

    /** {@code totalRevenue} divided by the number of non-cancelled orders. */
    private double averageOrderValue;
}
