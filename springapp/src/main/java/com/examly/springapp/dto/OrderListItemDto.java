package com.examly.springapp.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 10C — order row as returned by the paginated list endpoints.
 *
 * <p>A projection of {@code Order}: the identity/customer/amount fields the UI
 * needs plus a flattened item list. The owning {@code userId} is intentionally
 * NOT exposed (it is internal ownership metadata, not part of the list view).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderListItemDto {

    private Long id;

    private String customerName;

    private String customerEmail;

    private String shippingAddress;

    private LocalDateTime orderDate;

    private String status;

    private double totalAmount;

    /** Discount applied to this order (>= 0). */
    private double discountAmount;

    /** Coupon code applied, if any. */
    private String couponCode;

    /** Number of line items in this order. */
    private int itemCount;

    /** Flattened line items (empty list when the order has no items). */
    private List<OrderItemDto> items;

    /**
     * Latest stored payment (null = the order has no payment record yet).
     * Populated from real {@code Payment} rows in one batch query per page.
     */
    private PaymentSummaryDto payment;
}
