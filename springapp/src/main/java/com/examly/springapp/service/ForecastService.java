package com.examly.springapp.service;

import com.examly.springapp.model.Order;
import com.examly.springapp.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Sales forecasting using ordinary least squares (simple linear regression) over a daily
 * revenue series derived from REAL orders. Chosen because it is lightweight (no extra
 * dependencies) and appropriate for the short historical windows typical of this dataset.
 *
 * Pipeline: orders -> daily aggregation -> train/test split (80/20) -> fit -> evaluate
 * (MAE, RMSE, MAPE) -> refit on all data -> forecast next N days.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ForecastService {

    private final OrderRepository orderRepository;

    public Map<String, Object> forecast(int days, int historyDays) {
        double[] series = buildDailyRevenueSeries(historyDays);
        int n = series.length;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("historyDays", n);
        result.put("model", "LinearRegression");

        if (n < 5) {
            result.put("status", "INSUFFICIENT_DATA");
            result.put("message", "Not enough historical daily data to train a reliable model (need >= 5 days).");
            result.put("history", recentHistory(series));
            result.put("forecast", List.of());
            result.put("metrics", Map.of("mae", "N/A", "rmse", "N/A", "mape", "N/A"));
            return result;
        }

        int trainSize = Math.max(2, (int) Math.floor(n * 0.8));
        int testSize = n - trainSize;

        double[] train = Arrays.copyOfRange(series, 0, trainSize);
        double[] test = Arrays.copyOfRange(series, trainSize, n);

        double[] coef = fitLinear(train);
        double mae, rmse, mape;
        if (testSize > 0) {
            mae = 0; rmse = 0; mape = 0; int mapeCount = 0;
            for (int i = 0; i < testSize; i++) {
                double x = trainSize + i;
                double pred = coef[0] + coef[1] * x;
                double actual = test[i];
                double err = actual - pred;
                mae += Math.abs(err);
                rmse += err * err;
                if (actual != 0) { mape += Math.abs(err / actual); mapeCount++; }
            }
            mae /= testSize;
            rmse = Math.sqrt(rmse / testSize);
            mape = mapeCount > 0 ? (mape / mapeCount) * 100.0 : Double.NaN;
        } else {
            mae = rmse = mape = Double.NaN;
        }

        double[] full = fitLinear(series);

        List<Map<String, Object>> forecast = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 1; i <= days; i++) {
            double x = n - 1 + i;
            double pred = Math.max(0.0, full[0] + full[1] * x);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", today.plusDays(i).toString());
            m.put("predictedRevenue", round(pred));
            forecast.add(m);
        }

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("mae", Double.isNaN(mae) ? "N/A" : round(mae));
        metrics.put("rmse", Double.isNaN(rmse) ? "N/A" : round(rmse));
        metrics.put("mape", Double.isNaN(mape) ? "N/A" : round(mape));
        metrics.put("trainSize", trainSize);
        metrics.put("testSize", testSize);

        result.put("status", "OK");
        result.put("trendPerDay", round(full[1]));
        result.put("history", recentHistory(series));
        result.put("forecast", forecast);
        result.put("metrics", metrics);
        return result;
    }

    /** Daily revenue (non-cancelled) for the last `historyDays` days, oldest first. */
    private double[] buildDailyRevenueSeries(int historyDays) {
        LocalDate today = LocalDate.now();
        LocalDate start = today.minusDays(historyDays - 1L);
        Map<LocalDate, Double> buckets = new HashMap<>();
        for (int i = 0; i < historyDays; i++) {
            buckets.put(start.plusDays(i), 0.0);
        }
        for (Order o : orderRepository.findAllWithItems()) {
            if ("CANCELLED".equalsIgnoreCase(o.getStatus() == null ? "" : o.getStatus())) continue;
            LocalDateTime d = o.getOrderDate();
            if (d == null) continue;
            LocalDate day = d.toLocalDate();
            if (buckets.containsKey(day)) {
                buckets.computeIfPresent(day, (k, v) -> v + o.getTotalAmount());
            }
        }
        double[] series = new double[historyDays];
        for (int i = 0; i < historyDays; i++) {
            series[i] = buckets.getOrDefault(start.plusDays(i), 0.0);
        }
        return series;
    }

    /** OLS fit: returns [intercept, slope] for y = intercept + slope*x with x = 0..n-1. */
    static double[] fitLinear(double[] y) {
        int n = y.length;
        if (n == 0) return new double[]{0, 0};
        if (n == 1) return new double[]{y[0], 0};
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += y[i];
            sumXY += i * (double) y[i];
            sumXX += (double) i * i;
        }
        double denom = n * sumXX - sumX * sumX;
        double slope = denom == 0 ? 0 : (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        return new double[]{intercept, slope};
    }

    private List<Map<String, Object>> recentHistory(double[] series) {
        int n = series.length;
        int from = Math.max(0, n - 14);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> hist = new ArrayList<>();
        for (int i = from; i < n; i++) {
            int daysAgo = n - 1 - i;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", today.minusDays(daysAgo).toString());
            m.put("revenue", round(series[i]));
            hist.add(m);
        }
        return hist;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}