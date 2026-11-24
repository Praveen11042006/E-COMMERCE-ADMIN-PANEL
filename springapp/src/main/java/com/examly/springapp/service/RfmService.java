package com.examly.springapp.service;

import com.examly.springapp.model.Order;
import com.examly.springapp.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RFM (Recency, Frequency, Monetary) customer segmentation using real order history.
 *
 * Methodology:
 *  - Group all non-cancelled orders by customer (userId when present, else lower-cased email).
 *  - R = days since the customer's most recent order (lower is better).
 *  - F = number of orders (higher is better).
 *  - M = total amount spent (higher is better).
 *  - Each metric is scored 1..5 using quintile ranks computed from the ACTUAL customer base
 *    (not fixed magic thresholds), then a deterministic segment rule is applied.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RfmService {

    private final OrderRepository orderRepository;

    public Map<String, Object> getSegments() {
        List<Order> orders = orderRepository.findAllWithItems().stream()
                .filter(o -> !"CANCELLED".equalsIgnoreCase(o.getStatus() == null ? "" : o.getStatus()))
                .collect(Collectors.toList());

        LocalDateTime now = LocalDateTime.now();

        Map<String, CustomerAgg> agg = new LinkedHashMap<>();
        for (Order o : orders) {
            String key = key(o);
            if (key == null) continue;
            CustomerAgg c = agg.computeIfAbsent(key, k -> new CustomerAgg());
            c.key = key;
            c.email = o.getCustomerEmail();
            c.name = o.getCustomerName();
            c.frequency += 1;
            c.monetary += o.getTotalAmount();
            if (o.getOrderDate() != null) {
                if (c.lastOrder == null || o.getOrderDate().isAfter(c.lastOrder)) {
                    c.lastOrder = o.getOrderDate();
                }
            }
        }

        List<CustomerAgg> customers = new ArrayList<>(agg.values());
        for (CustomerAgg c : customers) {
            c.recencyDays = c.lastOrder == null ? 9999
                    : ChronoUnit.DAYS.between(c.lastOrder.toLocalDate(), now.toLocalDate());
        }

        double[] recency = customers.stream().mapToDouble(c -> c.recencyDays).sorted().toArray();
        double[] freq = customers.stream().mapToDouble(c -> c.frequency).sorted().toArray();
        double[] mon = customers.stream().mapToDouble(c -> c.monetary).sorted().toArray();

        for (CustomerAgg c : customers) {
            c.r = quintile(c.recencyDays, recency, false);
            c.f = quintile(c.frequency, freq, true);
            c.m = quintile(c.monetary, mon, true);
            c.segment = segment(c);
        }

        Map<String, List<CustomerAgg>> bySegment = customers.stream()
                .collect(Collectors.groupingBy(c -> c.segment, LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> segments = bySegment.entrySet().stream()
                .map(e -> {
                    List<CustomerAgg> list = e.getValue();
                    double revenue = list.stream().mapToDouble(c -> c.monetary).sum();
                    double avg = list.isEmpty() ? 0 : revenue / list.size();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("segment", e.getKey());
                    m.put("customerCount", list.size());
                    m.put("revenueContribution", round(revenue));
                    m.put("avgSpend", round(avg));
                    return m;
                })
                .sorted((a, b) -> Integer.compare((int) b.get("customerCount"), (int) a.get("customerCount")))
                .collect(Collectors.toList());

        List<Map<String, Object>> customerRows = customers.stream()
                .sorted((a, b) -> Double.compare(b.monetary, a.monetary))
                .limit(200)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("customer", c.name != null ? c.name : c.email);
                    m.put("email", c.email);
                    m.put("recencyDays", c.recencyDays);
                    m.put("frequency", c.frequency);
                    m.put("monetary", round(c.monetary));
                    m.put("r", c.r);
                    m.put("f", c.f);
                    m.put("m", c.m);
                    m.put("segment", c.segment);
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCustomers", customers.size());
        result.put("segments", segments);
        result.put("customers", customerRows);
        return result;
    }

    /**
     * Scores a value 1..5 using its percentile rank within the actual population.
     * Uses the (count-1)/(n-1) percentile so both "higher is better" and
     * "lower is better" (recency) can reach the full 1..5 range symmetrically.
     */
    static int quintile(double value, double[] sortedAsc, boolean higherIsBetter) {
        int n = sortedAsc.length;
        if (n <= 1) return 3;
        int count = 0;
        for (double x : sortedAsc) {
            if (x <= value) count++;
        }
        if (count == 0) count = 1;
        double pct = (double) (count - 1) / (n - 1); // 0..1
        double f = higherIsBetter ? pct : (1.0 - pct);
        int score = (int) Math.round(f * 4.0) + 1; // maps 0..1 -> 1..5
        return Math.max(1, Math.min(5, score));
    }

    /** Deterministic segment rules (documented in the report). */
    static String segment(CustomerAgg c) {
        if (c.r >= 4 && c.f >= 4 && c.m >= 4) return "High Value";
        if (c.f >= 4 && c.r >= 3) return "Loyal";
        if (c.f == 1 && c.r >= 4) return "New";
        if (c.r >= 4 && c.f >= 2) return "Potential Loyal";
        if (c.r <= 2 && c.f >= 3) return "At Risk";
        if (c.r <= 2 && c.f <= 2) return "Inactive";
        return "Regular";
    }

    private String key(Order o) {
        if (o.getUserId() != null) return "u:" + o.getUserId();
        return o.getCustomerEmail() != null ? "e:" + o.getCustomerEmail().toLowerCase() : null;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static class CustomerAgg {
        String key;
        String name;
        String email;
        long frequency;
        double monetary;
        long recencyDays;
        LocalDateTime lastOrder;
        int r, f, m;
        String segment;
    }
}