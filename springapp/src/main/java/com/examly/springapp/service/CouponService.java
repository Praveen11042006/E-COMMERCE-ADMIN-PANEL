package com.examly.springapp.service;

import com.examly.springapp.model.Coupon;
import com.examly.springapp.repository.CouponRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Coupon validation and management. All validation happens server-side; the client never
 * decides the discount amount.
 */
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> validate(String code, double subtotal) {
        Map<String, Object> res = new LinkedHashMap<>();
        if (code == null || code.isBlank()) {
            res.put("valid", false);
            res.put("message", "Coupon code is required.");
            res.put("discount", 0.0);
            return res;
        }
        Coupon c = couponRepository.findByCodeIgnoreCase(code.trim()).orElse(null);
        if (c == null) {
            res.put("valid", false);
            res.put("message", "Coupon not found.");
            res.put("discount", 0.0);
            return res;
        }
        if (!c.isActive()) {
            res.put("valid", false);
            res.put("message", "Coupon is no longer active.");
            res.put("discount", 0.0);
            return res;
        }
        if (c.getExpiryDate() != null && c.getExpiryDate().isBefore(LocalDateTime.now())) {
            res.put("valid", false);
            res.put("message", "Coupon has expired.");
            res.put("discount", 0.0);
            return res;
        }
        if (c.getUsageLimit() > 0 && c.getUsedCount() >= c.getUsageLimit()) {
            res.put("valid", false);
            res.put("message", "Coupon usage limit reached.");
            res.put("discount", 0.0);
            return res;
        }
        if (subtotal < c.getMinOrderAmount()) {
            res.put("valid", false);
            res.put("message", "Minimum order of " + c.getMinOrderAmount() + " required for this coupon.");
            res.put("discount", 0.0);
            return res;
        }
        double discount = computeDiscount(c, subtotal);
        res.put("valid", true);
        res.put("code", c.getCode());
        res.put("discount", round(discount));
        res.put("finalAmount", round(subtotal - discount));
        res.put("message", "Coupon applied successfully.");
        return res;
    }

    public double computeDiscount(Coupon c, double subtotal) {
        double d;
        if ("PERCENTAGE".equalsIgnoreCase(c.getDiscountType())) {
            d = subtotal * c.getDiscountValue() / 100.0;
            if (c.getMaxDiscount() > 0) {
                d = Math.min(d, c.getMaxDiscount());
            }
        } else { // FIXED
            d = c.getDiscountValue();
        }
        return Math.max(0, Math.min(d, subtotal));
    }

    /** Resolve a coupon for order creation against the REAL order subtotal; returns null when not applicable. */
    @Transactional(readOnly = true)
    public Coupon resolve(String code, double subtotal) {
        if (code == null || code.isBlank()) return null;
        Map<String, Object> v = validate(code, subtotal);
        if (!Boolean.TRUE.equals(v.get("valid"))) return null;
        return couponRepository.findByCodeIgnoreCase(code.trim()).orElse(null);
    }

    /**
     * Locks and consumes a coupon in the checkout transaction. Returning null
     * preserves the established behaviour for unavailable coupons.
     */
    @Transactional
    public Coupon reserveForOrder(String code, double subtotal) {
        if (code == null || code.isBlank()) return null;
        Coupon coupon = couponRepository.findByCodeIgnoreCaseForUpdate(code.trim()).orElse(null);
        if (!isApplicable(coupon, subtotal)) return null;
        coupon.setUsedCount(coupon.getUsedCount() + 1);
        return couponRepository.save(coupon);
    }

    @Transactional
    public Coupon markUsed(Coupon c) {
        if (c == null) return null;
        c.setUsedCount(c.getUsedCount() + 1);
        return couponRepository.save(c);
    }

    private boolean isApplicable(Coupon coupon, double subtotal) {
        if (coupon == null || !coupon.isActive()) return false;
        if (coupon.getExpiryDate() != null && coupon.getExpiryDate().isBefore(LocalDateTime.now())) return false;
        if (coupon.getUsageLimit() > 0 && coupon.getUsedCount() >= coupon.getUsageLimit()) return false;
        return subtotal >= coupon.getMinOrderAmount();
    }

    /**
     * Look up a coupon by code WITHOUT applying validation rules.
     * Used when releasing usage on cancellation (the coupon may have expired
     * or reached its limit since the order was placed — usage still must be restored).
     */
    @Transactional(readOnly = true)
    public Coupon findByCode(String code) {
        if (code == null || code.isBlank()) return null;
        return couponRepository.findByCodeIgnoreCase(code.trim()).orElse(null);
    }

    /**
     * Inverse of {@link #markUsed}: releases one usage when an order that consumed
     * the coupon is cancelled/removed. Guarded so {@code usedCount} can never drop
     * below zero (protects against legacy orders that never incremented usage).
     */
    @Transactional
    public Coupon markUsageRestored(Coupon c) {
        if (c == null) return null;
        if (c.getUsedCount() > 0) {
            c.setUsedCount(c.getUsedCount() - 1);
        }
        return couponRepository.save(c);
    }

    @Transactional(readOnly = true)
    public List<Coupon> getAll() {
        return couponRepository.findAll();
    }

    @Transactional
    public Coupon create(Coupon coupon) {
        if (coupon.getCode() == null || coupon.getCode().isBlank()) {
            throw new jakarta.validation.ValidationException("Coupon code is required");
        }
        if (couponRepository.existsByCodeIgnoreCase(coupon.getCode())) {
            throw new jakarta.validation.ValidationException("Coupon code already exists");
        }
        String type = coupon.getDiscountType() == null ? "" : coupon.getDiscountType().toUpperCase();
        if (!"PERCENTAGE".equals(type) && !"FIXED".equals(type)) {
            throw new jakarta.validation.ValidationException("discountType must be PERCENTAGE or FIXED");
        }
        if (coupon.getDiscountValue() <= 0) {
            throw new jakarta.validation.ValidationException("discountValue must be greater than 0");
        }
        coupon.setCode(coupon.getCode().trim().toUpperCase());
        coupon.setDiscountType(type);
        coupon.setActive(true);
        return couponRepository.save(coupon);
    }

    /**
     * Phase 13D — admin edit of an existing coupon.
     *
     * <p>Only editable discount fields are copied. {@code usedCount} and
     * {@code active} are deliberately preserved: usage is a historical fact and
     * activation is not part of the edit contract, so an edit can never reset
     * redemption counts or silently revive a retired coupon.
     */
    @Transactional
    public Coupon update(Long id, com.examly.springapp.dto.CouponRequest request) {
        Coupon coupon = couponRepository.findById(id)
                .orElseThrow(() -> new jakarta.validation.ValidationException("Coupon not found"));

        Coupon validated = request.toCoupon();
        couponRepository.findByCodeIgnoreCase(validated.getCode())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new jakarta.validation.ValidationException("Coupon code already exists");
                });

        coupon.setCode(validated.getCode());
        coupon.setDiscountType(validated.getDiscountType());
        coupon.setDiscountValue(validated.getDiscountValue());
        coupon.setMinOrderAmount(validated.getMinOrderAmount());
        coupon.setMaxDiscount(validated.getMaxDiscount());
        coupon.setExpiryDate(validated.getExpiryDate());
        coupon.setUsageLimit(validated.getUsageLimit());
        // usedCount and active intentionally untouched.
        return couponRepository.save(coupon);
    }

    @Transactional
    public void delete(Long id) {
        couponRepository.deleteById(id);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
