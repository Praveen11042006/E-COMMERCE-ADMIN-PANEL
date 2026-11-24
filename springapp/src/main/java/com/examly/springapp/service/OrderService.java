package com.examly.springapp.service;

import com.examly.springapp.dto.*;
import com.examly.springapp.model.*;
import com.examly.springapp.repository.*;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final CouponService couponService;

    private static final Set<String> VALID_STATUSES = Set.of(
        "PENDING", "PROCESSING", "SHIPPED", "DELIVERED", "CANCELLED"
    );

    /* --- Phase 10C: paging/filter sentinels --- */

    /** Owner scope meaning "no owner filter" (admin view of all orders). */
    private static final long NO_OWNER_SCOPE = -1L;

    /** Order-id filter meaning "no id filter". */
    private static final long NO_ID_FILTER = -1L;

    /** Lower bound used when no {@code dateFrom} filter is supplied. */
    private static final LocalDateTime DATE_MIN = LocalDateTime.of(1970, 1, 1, 0, 0);

    /** Upper bound used when no {@code dateTo} filter is supplied. */
    private static final LocalDateTime DATE_MAX = LocalDateTime.of(2999, 12, 31, 23, 59, 59);

    /** Lower bound used when no {@code minAmount} filter is supplied. */
    private static final double AMOUNT_MIN = -Double.MAX_VALUE;

    /** Upper bound used when no {@code maxAmount} filter is supplied. */
    private static final double AMOUNT_MAX = Double.MAX_VALUE;

    /** Maximum number of rows the CSV export may return. */
    private static final int EXPORT_MAX_ROWS = 100_000;
    private static final int EXPORT_BATCH_SIZE = 500;

    public Order createOrder(OrderCreateRequest request) {
        return createOrder(request, null);
    }

    public Order createOrder(OrderCreateRequest request, Long userId) {
        List<OrderItem> orderItems = new ArrayList<>();
        double total = 0.0;

        /*
         * Lock every requested product before checking stock. Sorting ids gives every
         * checkout the same lock order, preventing multi-product deadlocks. Aggregate
         * quantities first so a duplicated product line cannot bypass stock checks.
         */
        Map<Long, Integer> requestedQuantities = new TreeMap<>();
        for (OrderItemCreateRequest itemReq : request.getOrderItems()) {
            if (itemReq.getProductId() == null) {
                throw new ValidationException("Product id is required");
            }
            if (itemReq.getQuantity() <= 0) {
                throw new ValidationException("Quantity must be at least 1");
            }
            requestedQuantities.merge(itemReq.getProductId(), itemReq.getQuantity(), Integer::sum);
        }
        List<Product> lockedProducts = productRepository.findAllByIdInForUpdate(
                new ArrayList<>(requestedQuantities.keySet()));
        if (lockedProducts.size() != requestedQuantities.size()) {
            throw new ValidationException("Product not found");
        }
        Map<Long, Product> productsById = new HashMap<>();
        for (Product product : lockedProducts) {
            int requested = requestedQuantities.get(product.getId());
            if (product.getStockQuantity() < requested) {
                throw new ValidationException("Insufficient stock for product: " + product.getName());
            }
            productsById.put(product.getId(), product);
        }

        Order order = new Order();
        order.setCustomerName(request.getCustomerName());
        order.setCustomerEmail(request.getCustomerEmail());
        order.setShippingAddress(request.getShippingAddress());
        order.setOrderDate(LocalDateTime.now());
        order.setStatus("PENDING");
        order.setUserId(userId);

        for (OrderItemCreateRequest itemReq : request.getOrderItems()) {
            Product product = productsById.get(itemReq.getProductId());

            product.setStockQuantity(product.getStockQuantity() - itemReq.getQuantity());

            OrderItem item = OrderItem.builder()
                    .product(product)
                    .quantity(itemReq.getQuantity())
                    .priceAtPurchase(product.getPrice())
                    .order(order)
                    .build();

            orderItems.add(item);
            total += product.getPrice() * itemReq.getQuantity();
        }

        order.setOrderItems(orderItems);

        // Apply an optional coupon — validated server-side; a client cannot set the discount.
        double discount = 0.0;
        if (request.getCouponCode() != null && !request.getCouponCode().isBlank()) {
            Coupon coupon = couponService.reserveForOrder(request.getCouponCode(), total);
            if (coupon != null) {
                discount = couponService.computeDiscount(coupon, total);
                order.setCouponCode(coupon.getCode());
            }
        }
        order.setDiscountAmount(discount);
        order.setTotalAmount(Math.max(0.0, total - discount));

        return orderRepository.save(order);
    }

    /**
     * Phase 10B: admin order list. Uses the fetch-joined {@code findAllWithItems()}
     * (the same query analytics already rely on) so the response serializes each
     * order's items + products without an N+1 storm. Returned JSON is identical to
     * the previous {@code findAll()} — only the SQL statement count drops.
     */
    public List<Order> getAllOrders() {
        return orderRepository.findAllWithItems();
    }

    /**
     * Phase 10B: customer order history. Uses the owner-scoped fetch-join
     * {@code findByUserIdWithItems(userId)} to eliminate the N+1 on items/products
     * during serialization. Identity is still taken from the JWT by the controller;
     * data returned is identical to the previous lazy {@code findByUserId()}.
     */
    public List<Order> getOrdersByUserId(Long userId) {
        return orderRepository.findByUserIdWithItems(userId);
    }

    /**
     * Phase 10C: admin order list — one page of orders.
     *
     * <p>Paging happens in TWO steps so it stays database-side: first the ids
     * of the page are selected (filtered + sorted + LIMIT/OFFSET), then those
     * orders are loaded with their items and products in a single fetch-join
     * query. Combining both into one paged fetch-join query would make
     * Hibernate apply the limit in memory.
     *
     * @param userId  owner scope, or {@code null} for the admin (all orders) view
     * @param params  validated paging/sorting/filtering parameters
     */
    public PageResponse<OrderListItemDto> getOrdersPaged(Long userId, PageRequestParams params) {
        Page<Long> idPage = queryIds(userId, params, params.getPage(), params.getSize());
        List<Order> orders = loadOrdersInPageOrder(idPage.getContent());
        // Phase 13D: one batch query for the page's payment records.
        Map<Long, PaymentSummaryDto> payments = latestPaymentsFor(orders);
        List<OrderListItemDto> content = orders.stream()
                .map(order -> toListItemDto(order, payments.get(order.getId())))
                .toList();

        return PageResponse.of(content, idPage.getNumber(), idPage.getSize(), idPage.getTotalElements());
    }

    /**
     * Phase 10C: all orders matching the given filters (no paging).
     *
     * <p>Used by the CSV export so the file contains every matching row and not
     * only the rows of the current page.
     */
    public List<OrderListItemDto> getOrdersForExport(Long userId, PageRequestParams params) {
        Page<Long> idPage = queryIds(userId, params, 0, EXPORT_MAX_ROWS);
        return loadOrdersInPageOrder(idPage.getContent()).stream()
                .map(order -> toListItemDto(order, null))
                .toList();
    }

    /** Runs the id query for the given page with the given filters and sort. */
    private Page<Long> queryIds(Long userId, PageRequestParams params, int page, int size) {
        Sort sort = Sort.by(params.isSortAscending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                params.getSortBy());
        // A stable secondary sort on the id keeps the paging deterministic —
        // without it, rows sharing the sort value could repeat or be skipped
        // when walking through the pages.
        sort = sort.and(Sort.by(Sort.Direction.ASC, "id"));
        Pageable pageable = PageRequest.of(page, size, sort);

        return orderRepository.findIdsPaged(
                ownerScope(userId),
                statusFilter(params),
                searchPattern(params),
                searchId(params),
                params.getDateFrom() != null ? params.getDateFrom() : DATE_MIN,
                params.getDateTo() != null ? params.getDateTo() : DATE_MAX,
                DATE_MIN,
                DATE_MAX,
                params.getMinAmount() != null ? params.getMinAmount() : AMOUNT_MIN,
                params.getMaxAmount() != null ? params.getMaxAmount() : AMOUNT_MAX,
                AMOUNT_MIN,
                AMOUNT_MAX,
                pageable);
    }

    /**
     * Phase 10C: aggregate order metrics over the WHOLE table (not the page).
     *
     * <p>The admin KPI cards need the real totals, which can no longer be
     * derived from the rows of the current page. Revenue and the average order
     * value exclude cancelled orders.
     */
    public OrderStatsDto getOrderStats() {
        OrderRepository.OrderStatsAggregate aggregate = orderRepository.getOrderStatsAggregate();
        double revenue = aggregate.getTotalRevenue();
        long revenueOrderCount = aggregate.getRevenueOrderCount();
        double average = revenueOrderCount == 0 ? 0.0 : revenue / revenueOrderCount;

        return OrderStatsDto.builder()
                .total(aggregate.getTotal())
                .pending(aggregate.getPending())
                .processing(aggregate.getProcessing())
                .shipped(aggregate.getShipped())
                .delivered(aggregate.getDelivered())
                .cancelled(aggregate.getCancelled())
                .totalRevenue(round2(revenue))
                .averageOrderValue(round2(average))
                .build();
    }

    /**
     * Phase 10C: builds the CSV export of the given order rows.
     *
     * <p>The column layout matches the export the admin list used to build in
     * the browser, so the produced file stays the same.
     */
    public String buildOrdersCsv(List<OrderListItemDto> rows) {
        StringBuilder csv = new StringBuilder();
        csv.append("Order ID,Customer Name,Email,Phone,Status,Total Amount,Order Date,Shipping Address,Items Count");

        for (OrderListItemDto order : rows) {
            csv.append('\n');
            csv.append(order.getId()).append(',');
            csv.append(csvEscape(order.getCustomerName())).append(',');
            csv.append(csvEscape(order.getCustomerEmail())).append(',');
            // The order entity has no phone column; kept as an empty column so
            // the exported layout stays unchanged.
            csv.append(",");
            csv.append(csvEscape(order.getStatus())).append(',');
            csv.append(order.getTotalAmount()).append(',');
            csv.append(csvEscape(order.getOrderDate() == null ? "" : order.getOrderDate().toString())).append(',');
            csv.append(csvEscape(order.getShippingAddress())).append(',');
            csv.append(order.getItemCount());
        }

        return csv.toString();
    }

    /**
     * Streams CSV rows in bounded pages. Each page loads at most 500 orders
     * (and their items) before its DTOs become unreachable; no 100k-row list
     * or byte array is assembled in application memory.
     */
    @Transactional(readOnly = true)
    public void writeOrdersCsv(Long userId, PageRequestParams params, Writer writer) throws IOException {
        writer.write("Order ID,Customer Name,Email,Phone,Status,Total Amount,Order Date,Shipping Address,Items Count");
        int page = 0;
        int written = 0;
        while (written < EXPORT_MAX_ROWS) {
            int size = Math.min(EXPORT_BATCH_SIZE, EXPORT_MAX_ROWS - written);
            Page<Long> idPage = queryIds(userId, params, page, size);
            if (idPage.isEmpty()) break;
            for (Order order : loadOrdersInPageOrder(idPage.getContent())) {
                writer.write('\n');
                writeCsvRow(writer, toListItemDto(order, null));
                written++;
            }
            if (idPage.isLast() || idPage.getNumberOfElements() < size) break;
            page++;
        }
        writer.flush();
    }

    private void writeCsvRow(Writer writer, OrderListItemDto order) throws IOException {
        writer.write(String.valueOf(order.getId())); writer.write(',');
        writer.write(csvEscape(order.getCustomerName())); writer.write(',');
        writer.write(csvEscape(order.getCustomerEmail())); writer.write(",,");
        writer.write(csvEscape(order.getStatus())); writer.write(',');
        writer.write(String.valueOf(order.getTotalAmount())); writer.write(',');
        writer.write(csvEscape(order.getOrderDate() == null ? "" : order.getOrderDate().toString())); writer.write(',');
        writer.write(csvEscape(order.getShippingAddress())); writer.write(',');
        writer.write(String.valueOf(order.getItemCount()));
    }

    /** Quotes a CSV value and escapes embedded quotes. */
    private String csvEscape(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Phase 13D: latest stored payment per order for the given page.
     *
     * <p>A single {@code findByOrderIdIn} query serves the whole page (no N+1).
     * Only persisted payment fields are mapped; an order without a payment
     * record simply has no entry and serialises as {@code "payment": null}.
     */
    private Map<Long, PaymentSummaryDto> latestPaymentsFor(List<Order> orders) {
        if (orders == null || orders.isEmpty()) return Map.of();
        List<Long> ids = orders.stream().map(Order::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();

        Map<Long, Payment> latest = new HashMap<>();
        for (Payment payment : paymentRepository.findByOrderIdIn(ids)) {
            Payment current = latest.get(payment.getOrderId());
            if (current == null || isNewer(payment, current)) {
                latest.put(payment.getOrderId(), payment);
            }
        }

        Map<Long, PaymentSummaryDto> result = new HashMap<>();
        latest.forEach((orderId, payment) -> result.put(orderId, PaymentSummaryDto.builder()
                .status(payment.getStatus())
                .method(payment.getMethod())
                .amount(payment.getAmount())
                .transactionRef(payment.getTransactionRef())
                .invoiceNumber(payment.getInvoiceNumber())
                .createdAt(payment.getCreatedAt())
                .build()));
        return result;
    }

    /** Newest payment record wins; ids break ties when timestamps are equal/absent. */
    private boolean isNewer(Payment candidate, Payment current) {
        LocalDateTime a = candidate.getCreatedAt();
        LocalDateTime b = current.getCreatedAt();
        if (a != null && b != null && !a.equals(b)) return a.isAfter(b);
        if (a != null && b == null) return true;
        if (a == null && b != null) return false;
        return candidate.getId() != null && current.getId() != null
                && candidate.getId() > current.getId();
    }

    /** Projects an order (with its items) onto the list DTO. */
    private OrderListItemDto toListItemDto(Order order, PaymentSummaryDto payment) {
        List<OrderItem> items = order.getOrderItems();
        List<OrderItemDto> itemDtos = (items == null)
                ? List.of()
                : items.stream().map(this::toItemDto).toList();

        return OrderListItemDto.builder()
                .id(order.getId())
                .customerName(order.getCustomerName())
                .customerEmail(order.getCustomerEmail())
                .shippingAddress(order.getShippingAddress())
                .orderDate(order.getOrderDate())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .discountAmount(order.getDiscountAmount())
                .couponCode(order.getCouponCode())
                .itemCount(itemDtos.size())
                .items(itemDtos)
                .payment(payment)
                .build();
    }

    /** Projects one order line onto the flat item DTO. */
    private OrderItemDto toItemDto(OrderItem item) {
        Product product = item.getProduct();
        return OrderItemDto.builder()
                .id(item.getId())
                .productId(product != null ? product.getId() : null)
                .productName(product != null ? product.getName() : null)
                .category(product != null ? product.getCategory() : null)
                .quantity(item.getQuantity())
                .priceAtPurchase(item.getPriceAtPurchase())
                .subtotal(round2(item.getPriceAtPurchase() * item.getQuantity()))
                .build();
    }

    /**
     * Loads the orders of a page in ONE fetch-join query and restores the
     * order of the ids (the fetch-join query does not guarantee it).
     */
    private List<Order> loadOrdersInPageOrder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();

        List<Order> loaded = orderRepository.findAllByIdInWithItems(ids);
        Map<Long, Order> byId = new LinkedHashMap<>();
        loaded.forEach(order -> byId.put(order.getId(), order));

        List<Order> ordered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            Order order = byId.get(id);
            if (order != null) ordered.add(order);
        }
        return ordered;
    }

    /** Owner scope for the repository query: {@code -1} means "no owner filter". */
    private Long ownerScope(Long userId) {
        return userId == null ? NO_OWNER_SCOPE : userId;
    }

    /** Status filter for the repository query: {@code ""} means "no status filter". */
    private String statusFilter(PageRequestParams params) {
        return params.hasStatusFilter() ? params.getStatus() : "";
    }

    /** Search pattern for the repository query: {@code "%"} means "no text filter". */
    private String searchPattern(PageRequestParams params) {
        if (params.getSearch() == null) return "%";
        // Escape the LIKE wildcards so a literal % or _ in the search term
        // does not widen the match. '!' is the escape character of the
        // repository queries (a backslash would break on MySQL).
        String escaped = params.getSearch()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped.toLowerCase() + "%";
    }

    /** Order-id filter for the repository query: {@code -1} means "no id filter". */
    private Long searchId(PageRequestParams params) {
        if (params.getSearch() == null) return NO_ID_FILTER;
        try {
            return Long.parseLong(params.getSearch().trim());
        } catch (NumberFormatException e) {
            return NO_ID_FILTER;
        }
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public Order getOrderById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Order not found"));
    }

    /**
     * Verify that the given userId owns the order (or is null, meaning admin access).
     */
    public Order getOrderByIdForUser(Long orderId, Long userId) {
        Order order = getOrderById(orderId);
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new RuntimeException("Order not found");
        }
        return order;
    }

    public Order updateStatus(Long id, OrderStatusUpdateRequest request) {
        Order order = getOrderById(id);
        String newStatus = request.getStatus().toUpperCase().trim();

        if ("CANCELED".equals(newStatus)) {
            newStatus = "CANCELLED";
        }

        if (!VALID_STATUSES.contains(newStatus)) {
            throw new ValidationException("Invalid status: '" + request.getStatus() + "'. Allowed values: " + VALID_STATUSES);
        }

        String currentStatus = order.getStatus().toUpperCase();

        if ("DELIVERED".equals(currentStatus)) {
            throw new ValidationException("Cannot change status of delivered order");
        }

        if ("CANCELLED".equals(newStatus)) {
            if (!Set.of("PENDING", "PROCESSING").contains(currentStatus)) {
                throw new ValidationException(
                    "Can only cancel PENDING or PROCESSING orders. Current status: " + currentStatus
                );
            }
            restoreStock(order.getOrderItems());
            // Phase 9: releasing coupon usage mirrors the stock-restore guard,
            // so a repeated cancellation throws before reaching this point.
            restoreCouponUsage(order);
        }

        order.setStatus(newStatus);
        return orderRepository.save(order);
    }

    public Order cancelOrder(Long id, Long userId) {
        Order order = getOrderByIdForUser(id, userId);
        String currentStatus = order.getStatus().toUpperCase();

        if (!Set.of("PENDING", "PROCESSING").contains(currentStatus)) {
            throw new ValidationException(
                "Order cannot be cancelled in status: " + currentStatus +
                ". Only PENDING/PROCESSING orders can be cancelled."
            );
        }

        restoreStock(order.getOrderItems());
        restoreCouponUsage(order);
        order.setStatus("CANCELLED");
        return orderRepository.save(order);
    }

    private void restoreStock(List<OrderItem> items) {
        if (items == null) return;
        items.forEach(item -> {
            Product product = item.getProduct();
            product.setStockQuantity(product.getStockQuantity() + item.getQuantity());
            productRepository.save(product);
        });
    }

    /**
     * Phase 9: release one coupon usage when an order that consumed a coupon is
     * cancelled (or deleted before fulfillment). No-op for orders without a coupon.
     * The floor-at-zero guard lives in CouponService.markUsageRestored.
     */
    private void restoreCouponUsage(Order order) {
        if (order.getCouponCode() == null || order.getCouponCode().isBlank()) return;
        Coupon coupon = couponService.findByCode(order.getCouponCode());
        if (coupon != null) {
            couponService.markUsageRestored(coupon);
        }
    }

    /**
     * Delete order — restores stock if not already cancelled/delivered.
     */
    public void deleteOrder(Long id) {
        Order order = getOrderById(id);
        String status = order.getStatus() != null ? order.getStatus().toUpperCase() : "";
        // Restore stock only for active (non-cancelled, non-delivered) orders.
        // Cancelled orders already released stock AND coupon usage at cancel time,
        // so coupon usage is restored only alongside the stock restore.
        if (!Set.of("CANCELLED", "DELIVERED").contains(status)) {
            restoreStock(order.getOrderItems());
            restoreCouponUsage(order);
        }
        orderRepository.delete(order);
    }
}
