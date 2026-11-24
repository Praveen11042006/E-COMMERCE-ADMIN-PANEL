package com.examly.springapp.service;

import com.examly.springapp.model.Order;
import com.examly.springapp.model.OrderItem;
import com.examly.springapp.model.Product;
import com.examly.springapp.repository.OrderItemRepository;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes dashboard analytics strictly from real persisted orders/products.
 * No fabricated numbers — every metric is derived from the database.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;

    private static final int LOW_STOCK_THRESHOLD = 10;

    /** Threshold used by the dashboard's "Low Stock Alert" widget (stock &lt; 20). */
    private static final int LOW_STOCK_ALERT_THRESHOLD = 20;

    /** Widget shows the five most critical products only. */
    private static final int LOW_STOCK_ALERT_LIMIT = 5;

    /** Rows shown in the Top Products / Top Customers tables. */
    private static final int TOP_LIST_LIMIT = 8;

    /** Rows shown in the recent-orders table. */
    private static final int RECENT_ORDERS_LIMIT = 5;

    private static final LocalDateTime DATE_MIN = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime DATE_MAX = LocalDateTime.of(2999, 12, 31, 23, 59, 59);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public Map<String, Object> getSummary() {
        List<Order> allOrders = orderRepository.findAllWithItems();

        // Revenue excludes cancelled orders.
        List<Order> revenueOrders = allOrders.stream()
                .filter(o -> !"CANCELLED".equalsIgnoreCase(safeStatus(o)))
                .collect(Collectors.toList());

        double totalRevenue = revenueOrders.stream().mapToDouble(Order::getTotalAmount).sum();
        long totalOrders = allOrders.size();
        long completedOrders = allOrders.stream()
                .filter(o -> "DELIVERED".equalsIgnoreCase(safeStatus(o))).count();
        long cancelledOrders = allOrders.stream()
                .filter(o -> "CANCELLED".equalsIgnoreCase(safeStatus(o))).count();

        Set<String> customers = revenueOrders.stream()
                .map(this::customerKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        long totalProducts = productRepository.count();
        double avgOrderValue = revenueOrders.isEmpty() ? 0.0 : totalRevenue / revenueOrders.size();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalRevenue", round(totalRevenue));
        summary.put("totalOrders", totalOrders);
        summary.put("completedOrders", completedOrders);
        summary.put("cancelledOrders", cancelledOrders);
        summary.put("totalCustomers", customers.size());
        summary.put("totalProducts", totalProducts);
        summary.put("averageOrderValue", round(avgOrderValue));

        summary.put("monthlyRevenue", monthlyRevenue(revenueOrders));
        summary.put("dailyOrders", dailyOrders(allOrders, 14));
        summary.put("categorySales", categorySales(revenueOrders));
        summary.put("topProducts", topProducts(revenueOrders));
        summary.put("lowStockProducts", lowStockProducts());
        summary.put("orderStatusDistribution", statusDistribution(allOrders));

        return summary;
    }

    /**
     * Phase 13D — revenue per product category, aggregated in the database.
     *
     * <p>Replaces the dashboard's in-browser aggregation over products/orders.
     * The optional epoch-millisecond bounds mirror the dashboard date filter;
     * without bounds the whole history is aggregated (the "all" filter).
     * Cancelled orders are included, exactly as the previous chart did.
     */
    public List<Map<String, Object>> getCategorySales(Long fromMillis, Long toMillis) {
        LocalDateTime from = fromMillis == null ? DATE_MIN : toLocalDateTime(fromMillis);
        LocalDateTime to = toMillis == null ? DATE_MAX : toLocalDateTime(toMillis);

        return orderItemRepository.aggregateCategorySales(from, to).stream()
                .map(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", row.getCategory());
                    m.put("revenue", round(row.getRevenue() == null ? 0.0 : row.getRevenue()));
                    m.put("quantity", row.getQuantity() == null ? 0L : row.getQuantity());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * Phase 13D — inventory widget data without loading the catalogue.
     *
     * <p>{@code totalProducts} is a COUNT and the alert list is a bounded
     * LIMIT 5 query ordered by stock, so no product entities are materialised
     * beyond the five displayed rows.
     */
    public Map<String, Object> getInventory() {
        List<Map<String, Object>> lowStock = productRepository
                .findByStockQuantityLessThanOrderByStockQuantityAsc(
                        LOW_STOCK_ALERT_THRESHOLD, PageRequest.of(0, LOW_STOCK_ALERT_LIMIT))
                .stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("category", p.getCategory());
                    m.put("stockQuantity", p.getStockQuantity());
                    m.put("price", p.getPrice());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("totalProducts", productRepository.count());
        inventory.put("lowStockProducts", lowStock);
        return inventory;
    }

    private LocalDateTime toLocalDateTime(Long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    /**
     * Phase 13E — every KPI, table and non-time-series chart of the dashboard,
     * aggregated in the database for the requested window.
     *
     * <p>Replaces the dashboard download of all orders. Counts, sums, distinct
     * customers and per-product/per-customer groupings are computed by SQL;
     * only the eight rows shown in each table and five recent orders cross the
     * wire. {@code days} (the selected period length) is used exclusively to
     * compute the growth rate against the previous, equally long window.
     */
    public Map<String, Object> getOrderMetrics(Long fromMillis, Long toMillis, Integer days) {
        LocalDateTime from = fromMillis == null ? DATE_MIN : toLocalDateTime(fromMillis);
        LocalDateTime to = toMillis == null ? DATE_MAX : toLocalDateTime(toMillis);

        OrderRepository.OrderTotalsRow totals = orderRepository.getOrderTotals(from, to);
        double revenue = nz(totals == null ? null : totals.getRevenue());
        long orders = totals == null || totals.getOrders() == null ? 0L : totals.getOrders();
        long customers = totals == null || totals.getCustomers() == null ? 0L : totals.getCustomers();

        double growthRate = 0.0;
        if (days != null && days > 0) {
            LocalDateTime now = LocalDateTime.now();
            OrderRepository.OrderTotalsRow previous = orderRepository.getOrderTotals(
                    now.minusDays(days * 2L), now.minusDays(days));
            double previousRevenue = nz(previous == null ? null : previous.getRevenue());
            if (previousRevenue > 0) {
                growthRate = (revenue - previousRevenue) / previousRevenue * 100;
            }
        }

        List<Map<String, Object>> statusDistribution = orderRepository.getStatusCounts(from, to).stream()
                .map(row -> mapOf("status", row.getStatus(), "count", row.getCount()))
                .collect(Collectors.toList());

        List<Map<String, Object>> topProducts = orderRepository
                .getTopProducts(from, to, PageRequest.of(0, TOP_LIST_LIMIT)).stream()
                .map(row -> {
                    Map<String, Object> m = mapOf(
                            "id", row.getProductId(),
                            "name", row.getName(),
                            "category", row.getCategory() != null ? row.getCategory() : "Uncategorized",
                            "quantity", row.getQuantity() == null ? 0L : row.getQuantity(),
                            "revenue", round(nz(row.getRevenue())));
                    return m;
                })
                .collect(Collectors.toList());

        List<Map<String, Object>> topCustomers = orderRepository
                .getTopCustomers(from, to, PageRequest.of(0, TOP_LIST_LIMIT)).stream()
                .map(row -> {
                    Map<String, Object> m = mapOf(
                            "name", row.getName() != null ? row.getName() : "Unknown",
                            "email", row.getEmail(),
                            "totalSpent", round(nz(row.getTotalSpent())),
                            "totalOrders", row.getTotalOrders() == null ? 0L : row.getTotalOrders(),
                            "firstOrder", row.getFirstOrder(),
                            "lastOrder", row.getLastOrder());
                    return m;
                })
                .collect(Collectors.toList());

        List<OrderRepository.CustomerOrderCountRow> customerCounts =
                orderRepository.getCustomerOrderCounts(from, to);
        long repeatCustomers = customerCounts.stream()
                .filter(row -> row.getTotalOrders() != null && row.getTotalOrders() > 1)
                .count();

        List<Map<String, Object>> recentOrders = orderRepository
                .getRecentOrders(from, to, PageRequest.of(0, RECENT_ORDERS_LIMIT)).stream()
                .map(row -> mapOf(
                        "id", row.getId(),
                        "customerName", row.getCustomerName(),
                        "customerEmail", row.getCustomerEmail(),
                        "orderDate", row.getOrderDate(),
                        "status", row.getStatus(),
                        "totalAmount", nz(row.getTotalAmount()),
                        "itemCount", row.getItemCount() == null ? 0L : row.getItemCount()))
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revenue", revenue);
        result.put("orders", orders);
        result.put("customers", customers);
        result.put("averageOrderValue", orders == 0 ? 0.0 : revenue / orders);
        result.put("growthRate", round(growthRate));
        result.put("statusDistribution", statusDistribution);
        result.put("topProducts", topProducts);
        result.put("topCustomers", topCustomers);
        result.put("recentOrders", recentOrders);
        result.put("repeatCustomers", repeatCustomers);
        result.put("avgOrdersPerCustomer", customers == 0 ? 0.0 : (double) orders / customers);
        result.put("customerLifetimeValue", customers == 0 ? 0.0 : revenue / customers);
        return result;
    }

    /**
     * Phase 13E — the three scalar columns the revenue trend and activity
     * heatmap need. They stay client-side because their buckets are derived
     * from the BROWSER's timezone; aggregating them server-side would silently
     * shift hours/days for users outside the server zone.
     */
    public List<Map<String, Object>> getTimeSeries(Long fromMillis, Long toMillis) {
        LocalDateTime from = fromMillis == null ? DATE_MIN : toLocalDateTime(fromMillis);
        LocalDateTime to = toMillis == null ? DATE_MAX : toLocalDateTime(toMillis);

        return orderRepository.getTimeSeries(from, to).stream()
                .map(row -> mapOf(
                        "orderDate", row.getOrderDate(),
                        "totalAmount", nz(row.getTotalAmount()),
                        "customerEmail", row.getCustomerEmail()))
                .collect(Collectors.toList());
    }

    private double nz(Double value) {
        return value == null ? 0.0 : value;
    }

    private Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return m;
    }

    private List<Map<String, Object>> monthlyRevenue(List<Order> orders) {
        YearMonth now = YearMonth.now();
        Map<String, Double> buckets = new LinkedHashMap<>();
        for (int i = 5; i >= 0; i--) {
            buckets.put(now.minusMonths(i).format(MONTH_FMT), 0.0);
        }
        for (Order o : orders) {
            LocalDateTime d = o.getOrderDate();
            if (d == null) continue;
            String key = YearMonth.from(d).format(MONTH_FMT);
            buckets.computeIfPresent(key, (k, v) -> v + o.getTotalAmount());
        }
        return buckets.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("month", e.getKey());
                    m.put("revenue", round(e.getValue()));
                    return m;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> dailyOrders(List<Order> orders, int days) {
        Map<String, Long> buckets = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            buckets.put(today.minusDays(i).format(DAY_FMT), 0L);
        }
        for (Order o : orders) {
            LocalDateTime d = o.getOrderDate();
            if (d == null) continue;
            String key = d.toLocalDate().format(DAY_FMT);
            buckets.computeIfPresent(key, (k, v) -> v + 1);
        }
        return buckets.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", e.getKey());
                    m.put("orders", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> categorySales(List<Order> orders) {
        Map<String, double[]> agg = new LinkedHashMap<>(); // category -> [revenue, qty]
        for (Order o : orders) {
            List<OrderItem> items = o.getOrderItems();
            if (items == null) continue;
            for (OrderItem it : items) {
                Product p = it.getProduct();
                String cat = (p != null && p.getCategory() != null) ? p.getCategory() : "Uncategorized";
                double[] v = agg.computeIfAbsent(cat, k -> new double[2]);
                v[0] += it.getPriceAtPurchase() * it.getQuantity();
                v[1] += it.getQuantity();
            }
        }
        return agg.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("category", e.getKey());
                    m.put("revenue", round(e.getValue()[0]));
                    m.put("quantity", (long) e.getValue()[1]);
                    return m;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> topProducts(List<Order> orders) {
        Map<String, double[]> agg = new LinkedHashMap<>(); // name -> [qty, revenue]
        for (Order o : orders) {
            List<OrderItem> items = o.getOrderItems();
            if (items == null) continue;
            for (OrderItem it : items) {
                Product p = it.getProduct();
                String name = (p != null && p.getName() != null) ? p.getName() : "Unknown";
                double[] v = agg.computeIfAbsent(name, k -> new double[2]);
                v[0] += it.getQuantity();
                v[1] += it.getPriceAtPurchase() * it.getQuantity();
            }
        }
        return agg.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0]))
                .limit(5)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("quantitySold", (long) e.getValue()[0]);
                    m.put("revenue", round(e.getValue()[1]));
                    return m;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> lowStockProducts() {
        return productRepository.findByStockQuantityLessThanEqualOrderByStockQuantityAsc(LOW_STOCK_THRESHOLD)
                .stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("category", p.getCategory());
                    m.put("stockQuantity", p.getStockQuantity());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> statusDistribution(List<Order> orders) {
        Map<String, Long> dist = new LinkedHashMap<>();
        for (Order o : orders) {
            dist.merge(safeStatus(o), 1L, Long::sum);
        }
        return dist.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("status", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private String safeStatus(Order o) {
        return o.getStatus() == null ? "UNKNOWN" : o.getStatus().toUpperCase();
    }

    private String customerKey(Order o) {
        if (o.getUserId() != null) return "u:" + o.getUserId();
        return o.getCustomerEmail() != null ? "e:" + o.getCustomerEmail().toLowerCase() : null;
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

