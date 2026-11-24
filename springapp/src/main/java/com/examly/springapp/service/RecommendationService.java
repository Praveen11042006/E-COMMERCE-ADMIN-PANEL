package com.examly.springapp.service;

import com.examly.springapp.model.Order;
import com.examly.springapp.model.OrderItem;
import com.examly.springapp.model.Product;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Product recommendation engine.
 *
 * Strategy (documented for the report):
 *  1. Popularity signal: total quantity of each product sold across non-cancelled orders.
 *  2. Personalisation (content/category based): if the authenticated user has purchased
 *     products before, recommend in-stock products from the SAME categories that the user
 *     has not yet bought.
 *  3. Cold start: users with no history receive the globally most popular in-stock products.
 *
 * Score = 0.7 * popularityNorm + 0.3 * stockNorm, where popularityNorm is qtySold/maxQtySold
 * and stockNorm is min(stock, 100)/100. When no sales exist, score falls back to stockNorm.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public Map<String, Object> recommend(Long userId, int limit) {
        List<Order> orders = orderRepository.findAllWithItems().stream()
                .filter(o -> !"CANCELLED".equalsIgnoreCase(o.getStatus() == null ? "" : o.getStatus()))
                .collect(Collectors.toList());

        // Popularity map: productId -> qty sold
        Map<Long, Long> popularity = new HashMap<>();
        // Per-user purchases
        Set<Long> userProductIds = new HashSet<>();
        Set<String> userCategories = new HashSet<>();

        for (Order o : orders) {
            List<OrderItem> items = o.getOrderItems();
            if (items == null) continue;
            boolean mine = userId != null && userId.equals(o.getUserId());
            for (OrderItem it : items) {
                Product p = it.getProduct();
                if (p == null || p.getId() == null) continue;
                popularity.merge(p.getId(), (long) it.getQuantity(), Long::sum);
                if (mine) {
                    userProductIds.add(p.getId());
                    if (p.getCategory() != null) userCategories.add(p.getCategory());
                }
            }
        }

        long maxPopularity = popularity.values().stream().mapToLong(Long::longValue).max().orElse(0L);

        List<Product> all = productRepository.findAll();
        List<Product> inStock = all.stream()
                .filter(p -> p.getStockQuantity() > 0)
                .collect(Collectors.toList());

        boolean personalised = !userCategories.isEmpty();

        List<Product> candidates = inStock.stream()
                .filter(p -> !userProductIds.contains(p.getId()))
                .filter(p -> !personalised || userCategories.contains(p.getCategory()))
                .collect(Collectors.toList());

        // Cold-start fallback: if nothing qualifies, use all in-stock products.
        boolean coldStart = candidates.isEmpty();
        if (coldStart) {
            candidates = inStock;
        }

        List<Map<String, Object>> recs = candidates.stream()
                .map(p -> {
                    long sold = popularity.getOrDefault(p.getId(), 0L);
                    double popularityNorm = maxPopularity == 0 ? 0.0 : (double) sold / maxPopularity;
                    double stockNorm = Math.min(p.getStockQuantity(), 100) / 100.0;
                    double score = maxPopularity == 0
                            ? round(stockNorm)
                            : round(0.7 * popularityNorm + 0.3 * stockNorm);

                    String reason;
                    if (coldStart) {
                        reason = sold > 0 ? "Popular product" : "Available in stock";
                    } else {
                        reason = "Based on your interest in " + p.getCategory();
                    }

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("productId", p.getId());
                    m.put("name", p.getName());
                    m.put("category", p.getCategory());
                    m.put("price", p.getPrice());
                    m.put("stockQuantity", p.getStockQuantity());
                    m.put("imageUrl", p.getImageUrl());
                    m.put("reason", reason);
                    m.put("score", score);
                    return m;
                })
                .sorted((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")))
                .limit(limit)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", coldStart ? "COLD_START_POPULARITY" : "CATEGORY_BASED");
        result.put("personalised", personalised && !coldStart);
        result.put("recommendations", recs);
        return result;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
