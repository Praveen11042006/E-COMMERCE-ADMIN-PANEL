package com.examly.springapp.controller;

import com.examly.springapp.dto.CouponRequest;
import com.examly.springapp.dto.CouponValidateRequest;
import com.examly.springapp.model.Coupon;
import com.examly.springapp.service.AuditService;
import com.examly.springapp.service.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;
    private final AuditService auditService;

    /** Validate a coupon for a given subtotal. Available to authenticated customers. */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody CouponValidateRequest req) {
        return ResponseEntity.ok(couponService.validate(req.getCode(), req.getSubtotal()));
    }

    /** List all coupons (ADMIN). */
    @GetMapping
    public ResponseEntity<List<Coupon>> list() {
        return ResponseEntity.ok(couponService.getAll());
    }

    /** Create a coupon (ADMIN). */
    @PostMapping
    public ResponseEntity<Coupon> create(@Valid @RequestBody CouponRequest request) {
        Coupon created = couponService.create(request.toCoupon());
        auditService.logCurrentUser("COUPON_CREATE", "Coupon", created.getId(), "Created coupon " + created.getCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Edit a coupon's discount fields (ADMIN). Usage count and activation are preserved. */
    @PutMapping("/{id}")
    public ResponseEntity<Coupon> update(@PathVariable Long id,
                                         @Valid @RequestBody CouponRequest request) {
        Coupon updated = couponService.update(id, request);
        auditService.logCurrentUser("COUPON_UPDATE", "Coupon", id, "Updated coupon " + updated.getCode());
        return ResponseEntity.ok(updated);
    }

    /** Delete a coupon (ADMIN). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        couponService.delete(id);
        auditService.logCurrentUser("COUPON_DELETE", "Coupon", id, "Deleted coupon");
        return ResponseEntity.noContent().build();
    }
}
