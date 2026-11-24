package com.examly.springapp.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A discount coupon. Validated server-side before it can be applied to an order.
 */
@Entity
@Table(name = "coupons")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** PERCENTAGE or FIXED */
    @Column(nullable = false)
    private String discountType;

    /** For PERCENTAGE: e.g. 10 = 10%. For FIXED: absolute amount off. */
    @Column(nullable = false)
    private double discountValue;

    /** Minimum order subtotal required for the coupon to apply. */
    private double minOrderAmount;

    /** Maximum discount cap (only meaningful for PERCENTAGE). 0 = no cap. */
    private double maxDiscount;

    private LocalDateTime expiryDate;

    /** 0 = unlimited usage. */
    private int usageLimit;

    private int usedCount;

    private boolean active;
}
