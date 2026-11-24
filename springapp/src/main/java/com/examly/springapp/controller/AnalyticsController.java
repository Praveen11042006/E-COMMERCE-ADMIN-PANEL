package com.examly.springapp.controller;

import com.examly.springapp.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    /** Aggregated dashboard metrics computed from real order/product data (ADMIN). */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        return ResponseEntity.ok(analyticsService.getSummary());
    }

    /**
     * Phase 13D — revenue per category, aggregated by the database.
     *
     * <p>Optional epoch-millisecond bounds correspond to the dashboard's date
     * filter; omitting them aggregates the whole history.
     */
    @GetMapping("/category-sales")
    public ResponseEntity<List<Map<String, Object>>> categorySales(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return ResponseEntity.ok(analyticsService.getCategorySales(from, to));
    }

    /** Phase 13D — total product count plus the five lowest-stock products. */
    @GetMapping("/inventory")
    public ResponseEntity<Map<String, Object>> inventory() {
        return ResponseEntity.ok(analyticsService.getInventory());
    }

    /**
     * Phase 13E — KPIs, tables and status chart aggregated by the database
     * for the requested window. {@code days} only feeds the growth rate.
     */
    @GetMapping("/order-metrics")
    public ResponseEntity<Map<String, Object>> orderMetrics(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to,
            @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(analyticsService.getOrderMetrics(from, to, days));
    }

    /** Phase 13E — minimal (date, amount, email) projection for the time-series charts. */
    @GetMapping("/time-series")
    public ResponseEntity<List<Map<String, Object>>> timeSeries(
            @RequestParam(required = false) Long from,
            @RequestParam(required = false) Long to) {
        return ResponseEntity.ok(analyticsService.getTimeSeries(from, to));
    }
}
