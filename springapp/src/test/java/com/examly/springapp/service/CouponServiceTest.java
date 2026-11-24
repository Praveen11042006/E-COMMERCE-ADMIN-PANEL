package com.examly.springapp.service;

import com.examly.springapp.model.Coupon;
import com.examly.springapp.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for coupon validation logic (repository mocked — pure business rules).
 */
public class CouponServiceTest {

    private CouponRepository repo;
    private CouponService service;

    @BeforeEach
    void setup() {
        repo = Mockito.mock(CouponRepository.class);
        service = new CouponService(repo);
    }

    private Coupon percentage(double value, double maxDiscount, double minOrder) {
        return Coupon.builder()
                .code("SAVE10").discountType("PERCENTAGE").discountValue(value)
                .maxDiscount(maxDiscount).minOrderAmount(minOrder)
                .active(true).usageLimit(0).usedCount(0)
                .build();
    }

    @Test
    void validate_unknownCoupon() {
        Mockito.when(repo.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        Map<String, Object> res = service.validate("NOPE", 100);
        assertEquals(false, res.get("valid"));
    }

    @Test
    void validate_expiredCoupon() {
        Coupon c = percentage(10, 0, 0);
        c.setExpiryDate(LocalDateTime.now().minusDays(1));
        Mockito.when(repo.findByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(c));
        assertEquals(false, service.validate("SAVE10", 100).get("valid"));
    }

    @Test
    void validate_belowMinimumOrder() {
        Coupon c = percentage(10, 0, 500);
        Mockito.when(repo.findByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(c));
        assertEquals(false, service.validate("SAVE10", 100).get("valid"));
    }

    @Test
    void validate_percentageWithCap() {
        Coupon c = percentage(50, 100, 0); // 50% capped at 100
        Mockito.when(repo.findByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(c));
        Map<String, Object> res = service.validate("SAVE10", 1000);
        assertEquals(true, res.get("valid"));
        assertEquals(100.0, (double) res.get("discount"), 1e-9); // capped
        assertEquals(900.0, (double) res.get("finalAmount"), 1e-9);
    }

    @Test
    void computeDiscount_fixedNeverExceedsSubtotal() {
        Coupon c = Coupon.builder().code("FLAT500").discountType("FIXED")
                .discountValue(500).minOrderAmount(0).active(true).build();
        assertEquals(300.0, service.computeDiscount(c, 300), 1e-9);
    }

    @Test
    void validate_usageLimitReached() {
        Coupon c = percentage(10, 0, 0);
        c.setUsageLimit(5);
        c.setUsedCount(5);
        Mockito.when(repo.findByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(c));
        assertEquals(false, service.validate("SAVE10", 100).get("valid"));
    }
    /**
     * D1 regression: the coupon resolver used to validate against Double.MAX_VALUE, which
     * bypassed minOrderAmount when an order was created. It must use the REAL subtotal.
     */
    @Test
    void resolve_belowMinimumOrderReturnsNull() {
        Coupon c = percentage(10, 0, 500); // minimum order 500
        Mockito.when(repo.findByCodeIgnoreCase("SAVE10")).thenReturn(Optional.of(c));
        assertNull(service.resolve("SAVE10", 100), "coupon must not resolve below its minimum order amount");
        assertNotNull(service.resolve("SAVE10", 600));
    }
}
