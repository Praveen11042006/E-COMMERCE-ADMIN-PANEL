package com.examly.springapp.dto;

import com.examly.springapp.model.Coupon;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Phase 13B — validated request contract for coupon creation.
 *
 * <p>The response is still the persisted {@link Coupon}. The service keeps its own
 * business rules (duplicate code, forced uppercase code, activation) as a second line
 * of defence; this DTO stops malformed data before it reaches JPA at all.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponRequest {

    @NotBlank(message = "Coupon code is required")
    @Size(max = 50, message = "Coupon code must not exceed 50 characters")
    @Pattern(regexp = "[A-Za-z0-9_-]+",
            message = "Coupon code may contain only letters, digits, dashes and underscores")
    private String code;

    @NotBlank(message = "Discount type is required")
    @Pattern(regexp = "(?i)PERCENTAGE|FIXED", message = "Discount type must be PERCENTAGE or FIXED")
    private String discountType;

    @NotNull(message = "Discount value is required")
    @DecimalMin(value = "0.01", message = "Discount value must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Discount value must have at most 2 decimal digits")
    private Double discountValue;

    /** Minimum order subtotal required; must not be negative. */
    @DecimalMin(value = "0.0", message = "Minimum order amount must not be negative")
    private Double minOrderAmount;

    /** Cap for percentage coupons (0 or empty = no cap); must not be negative. */
    @DecimalMin(value = "0.0", message = "Maximum discount must not be negative")
    private Double maxDiscount;

    /** 0 or empty = unlimited usage. */
    @PositiveOrZero(message = "Usage limit must not be negative")
    @Max(value = 1_000_000, message = "Usage limit must not exceed 1000000")
    private Integer usageLimit;

    /** Optional; a coupon created already expired is almost always a mistake. */
    @Future(message = "Expiry date must be in the future")
    private LocalDateTime expiryDate;

    /** Percentage coupons cannot discount more than the whole order. */
    @JsonIgnore
    @AssertTrue(message = "Percentage discount value must not exceed 100")
    public boolean isPercentageDiscountWithinRange() {
        if (discountValue == null || !"PERCENTAGE".equalsIgnoreCase(discountType)) {
            return true;
        }
        return discountValue <= 100.0;
    }

    /** A percentage cap only makes sense for percentage coupons. */
    @JsonIgnore
    @AssertTrue(message = "Maximum discount is only supported for PERCENTAGE coupons")
    public boolean isMaxDiscountOnlyForPercentage() {
        if (maxDiscount == null || maxDiscount <= 0) {
            return true;
        }
        return "PERCENTAGE".equalsIgnoreCase(discountType);
    }

    public Coupon toCoupon() {
        return Coupon.builder()
                .code(code == null ? null : code.trim().toUpperCase())
                .discountType(discountType == null ? null : discountType.trim().toUpperCase())
                .discountValue(discountValue == null ? 0d : discountValue)
                .minOrderAmount(minOrderAmount == null ? 0d : minOrderAmount)
                .maxDiscount(maxDiscount == null ? 0d : maxDiscount)
                .expiryDate(expiryDate)
                .usageLimit(usageLimit == null ? 0 : usageLimit)
                .usedCount(0)
                .active(true)
                .build();
    }
}
