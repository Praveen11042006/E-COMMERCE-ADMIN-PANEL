package com.examly.springapp.service;

import com.examly.springapp.model.Order;
import com.examly.springapp.model.OrderItem;
import com.examly.springapp.model.Product;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Inventory demand prediction derived from real sales history.
 *
 * Documented calculation per product:
 *   totalSold        = sum of quantity across non-cancelled orders
 *   salesSpanDays    = days between first and last sale (min 1)
 *   avgDailyDemand   = totalSold / salesSpanDays
 *   LEAD_TIME_DAYS   = 14 (assumed restock lead time)
 *   SAFETY_DAYS      = 7  (buffer)
 *   predictedDemand  = avgDailyDemand * LEAD_TIME_DAYS
 *   reorderThreshold = avgDailyDemand * (LEAD_TIME_DAYS + SAFETY_DAYS)
 *
 * Status:
 *   Critical             -> stock <= avgDailyDemand * 3
 *   Reorder Recommended  -> stock <= reorderThreshold
 *   Low Stock            -> stock <= reorderThreshold * 1.5
 *   Healthy Stock        -> otherwise
 *   (No sales history uses a fixed low-stock threshold of 10.)
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DemandService {

    private static final int LEAD_TIME_DAYS = 14;
    private static final int SAFETY_DAYS = 7;
    private static final int NO_HISTORY_LOW_STOCK = 10;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public Map<String, Object> predict() {
        List<Order> orders = orderRepository.findAllWithItems().stream()
                .filter(o -> !"CANCELLED".equalsIgnoreCase(o.getStatus() == null ? "" : o.getStatus()))
                .collect(Collectors.toList());

        Map<Long, double[]> stats = new HashMap<>(); // productId -> [totalSold, firstEpochDay, lastEpochDay]
        for (Order o : orders) {
            LocalDateTime d = o.getOrderDate();
            double epoch = d == null ? -1 : d.toLocalDate().toEpochDay();
            List<OrderItem> items = o.getOrderItems();
            if (items == null) continue;
            for (OrderItem it : items) {
                Product p = it.getProduct();
                if (p == null || p.getId() == null) continue;
                double[] s = stats.computeIfAbsent(p.getId(), k -> new double[]{0, -1, -1});
                s[0] += it.getQuantity();
                if (epoch >= 0) {
                    if (s[1] < 0 || epoch < s[1]) s[1] = epoch;
                    if (epoch > s[2]) s[2] = epoch;
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Product p : productRepository.findAll()) {
            double[] s = stats.getOrDefault(p.getId(), new double[]{0, -1, -1});
            double totalSold = s[0];
            long spanDays = (s[1] >= 0 && s[2] >= 0) ? Math.max(1, (long) (s[2] - s[1]) + 1) : 1;
            double avgDaily = totalSold / spanDays;
            double predictedDemand = avgDaily * LEAD_TIME_DAYS;
            double reorderThreshold = avgDaily * (LEAD_TIME_DAYS + SAFETY_DAYS);

            String status;
            if (totalSold == 0) {
                status = p.getStockQuantity() <= NO_HISTORY_LOW_STOCK ? "Low Stock" : "Healthy Stock";
            } else if (p.getStockQuantity() <= avgDaily * 3) {
                status = "Critical";
            } else if (p.getStockQuantity() <= reorderThreshold) {
                status = "Reorder Recommended";
            } else if (p.getStockQuantity() <= reorderThreshold * 1.5) {
                status = "Low Stock";
            } else {
                status = "Healthy Stock";
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productId", p.getId());
            m.put("name", p.getName());
            m.put("category", p.getCategory());
            m.put("currentStock", p.getStockQuantity());
            m.put("totalSold", (long) totalSold);
            m.put("avgDailyDemand", round(avgDaily));
            m.put("predictedDemand", round(predictedDemand));
            m.put("reorderThreshold", round(reorderThreshold));
            m.put("status", status);
            result.add(m);
        }

        result.sort((a, b) -> Integer.compare(statusRank((String) a.get("status")), statusRank((String) b.get("status"))));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("leadTimeDays", LEAD_TIME_DAYS);
        out.put("safetyDays", SAFETY_DAYS);
        out.put("products", result);
        return out;
    }

    private int statusRank(String status) {
        return switch (status) {
            case "Critical" -> 0;
            case "Reorder Recommended" -> 1;
            case "Low Stock" -> 2;
            default -> 3;
        };
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
