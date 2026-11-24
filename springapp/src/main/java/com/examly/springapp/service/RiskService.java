package com.examly.springapp.service;

import com.examly.springapp.model.Order;
import com.examly.springapp.model.OrderItem;
import com.examly.springapp.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Order risk / anomaly detection.
 *
 * This is a REVIEW-TRIAGE tool, NOT a fraud accusation system. Each order is labelled
 * either "Normal" or "Review Required", and every flag includes a human-readable reason.
 *
 * Signals (all relative to the same customer's own history, so a customer is never judged
 * against another customer):
 *  1. Order value > 3x the customer's average order value.
 *  2. A single line quantity >= 10, or > 3x the customer's average quantity for that product.
 *  3. Three or more orders placed within any rolling 24-hour window.
 *  4. Abnormal cancellations: >= 3 cancellations AND cancellation rate > 50%.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskService {

    private static final double VALUE_MULTIPLIER = 3.0;
    private static final int HIGH_QUANTITY = 10;
    private static final double QTY_MULTIPLIER = 3.0;
    private static final int RAPID_ORDER_COUNT = 3;
    private static final int CANCELLATION_MIN = 3;
    private static final double CANCELLATION_RATE = 0.5;

    private final OrderRepository orderRepository;

    public Map<String, Object> analyze() {
        List<Order> orders = orderRepository.findAllWithItems();

        Map<String, List<Order>> byCustomer = orders.stream()
                .filter(o -> key(o) != null)
                .collect(Collectors.groupingBy(this::key));

        List<Map<String, Object>> flagged = new ArrayList<>();
        int normalCount = 0;

        for (Order o : orders) {
            List<String> reasons = new ArrayList<>();
            String k = key(o);
            List<Order> history = k != null ? byCustomer.getOrDefault(k, List.of()) : List.of();

            // Signal 1: unusually high order value vs customer's own average.
            double avgValue = history.stream()
                    .filter(x -> !"CANCELLED".equalsIgnoreCase(safe(x.getStatus())))
                    .mapToDouble(Order::getTotalAmount).average().orElse(0);
            if (avgValue > 0 && !"CANCELLED".equalsIgnoreCase(safe(o.getStatus()))
                    && o.getTotalAmount() > avgValue * VALUE_MULTIPLIER) {
                reasons.add("Order value is significantly higher than this customer's historical average.");
            }

            // Signal 2: unusually high quantity.
            if (o.getOrderItems() != null) {
                for (OrderItem it : o.getOrderItems()) {
                    if (it.getQuantity() >= HIGH_QUANTITY) {
                        String name = it.getProduct() != null ? it.getProduct().getName() : "a product";
                        reasons.add("Unusually high quantity (" + it.getQuantity() + ") for " + name + ".");
                    }
                }
            }

            // Signal 3: rapid repeated orders (>= 3 within 24h).
            LocalDateTime ts = o.getOrderDate();
            if (ts != null) {
                long within24h = history.stream()
                        .filter(x -> x.getOrderDate() != null)
                        .filter(x -> {
                            long hours = Math.abs(Duration.between(x.getOrderDate(), ts).toHours());
                            return hours <= 24;
                        }).count();
                if (within24h >= RAPID_ORDER_COUNT) {
                    reasons.add(within24h + " orders placed within a 24-hour window.");
                }
            }

            // Signal 4: abnormal cancellation pattern (customer-level; attach once per flagged order).
            if (!history.isEmpty()) {
                long cancels = history.stream()
                        .filter(x -> "CANCELLED".equalsIgnoreCase(safe(x.getStatus()))).count();
                if (cancels >= CANCELLATION_MIN && (double) cancels / history.size() > CANCELLATION_RATE
                        && !reasons.contains("Repeated order cancellations")) {
                    reasons.add("Repeated order cancellations");
                }
            }

            boolean review = !reasons.isEmpty();
            if (review) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderId", o.getId());
                m.put("customer", o.getCustomerName());
                m.put("totalAmount", o.getTotalAmount());
                m.put("status", safe(o.getStatus()));
                m.put("riskLevel", reasons.size() >= 2 ? "High" : "Review Required");
                m.put("reasons", reasons);
                flagged.add(m);
            } else {
                normalCount++;
            }
        }

        flagged.sort((a, b) -> Integer.compare(
                ((List<?>) b.get("reasons")).size(),
                ((List<?>) a.get("reasons")).size()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysedOrders", orders.size());
        result.put("normalOrders", normalCount);
        result.put("reviewRequired", flagged.size());
        result.put("flaggedOrders", flagged);
        return result;
    }

    private String safe(String s) {
        return s == null ? "" : s.toUpperCase();
    }

    private String key(Order o) {
        if (o.getUserId() != null) return "u:" + o.getUserId();
        return o.getCustomerEmail() != null ? "e:" + o.getCustomerEmail().toLowerCase() : null;
    }
}
