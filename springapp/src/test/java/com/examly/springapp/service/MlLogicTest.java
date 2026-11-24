package com.examly.springapp.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-logic unit tests for the ML/analytics algorithms (no Spring context needed).
 */
public class MlLogicTest {

    @Test
    void forecast_fitLinear_recoversSlopeAndIntercept() {
        double[] y = {1, 2, 3, 4, 5}; // y = 1 + 1*x
        double[] coef = ForecastService.fitLinear(y);
        assertEquals(1.0, coef[0], 1e-9, "intercept");
        assertEquals(1.0, coef[1], 1e-9, "slope");
    }

    @Test
    void forecast_fitLinear_flatSeriesHasZeroSlope() {
        double[] coef = ForecastService.fitLinear(new double[]{7, 7, 7, 7});
        assertEquals(0.0, coef[1], 1e-9);
        assertEquals(7.0, coef[0], 1e-9);
    }

    @Test
    void rfm_quintile_higherIsBetter() {
        double[] sorted = {1, 2, 3, 4, 5};
        assertEquals(5, RfmService.quintile(5, sorted, true));
        assertEquals(1, RfmService.quintile(1, sorted, true));
    }

    @Test
    void rfm_quintile_lowerIsBetter_forRecency() {
        double[] sorted = {1, 2, 3, 4, 5};
        // Recency 1 (most recent) should score highest.
        assertEquals(5, RfmService.quintile(1, sorted, false));
        // Recency 5 (oldest) should score lowest.
        assertEquals(1, RfmService.quintile(5, sorted, false));
    }

    @Test
    void rfm_segment_rules() {
        assertEquals("High Value", RfmService.segment(agg(5, 5, 5)));
        assertEquals("Loyal", RfmService.segment(agg(3, 5, 2)));
        assertEquals("New", RfmService.segment(agg(5, 1, 2)));
        assertEquals("Potential Loyal", RfmService.segment(agg(5, 2, 2)));
        assertEquals("At Risk", RfmService.segment(agg(1, 3, 2)));
        assertEquals("Inactive", RfmService.segment(agg(1, 1, 1)));
        assertEquals("Regular", RfmService.segment(agg(3, 3, 3)));
    }

    private RfmService.CustomerAgg agg(int r, int f, int m) {
        RfmService.CustomerAgg c = new RfmService.CustomerAgg();
        c.r = r;
        c.f = f;
        c.m = m;
        return c;
    }
}
