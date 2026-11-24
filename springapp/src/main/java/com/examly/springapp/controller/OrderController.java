package com.examly.springapp.controller;

import com.examly.springapp.dto.OrderCreateRequest;
import com.examly.springapp.dto.OrderListItemDto;
import com.examly.springapp.dto.OrderStatusUpdateRequest;
import com.examly.springapp.dto.OrderStatsDto;
import com.examly.springapp.dto.PageRequestParams;
import com.examly.springapp.dto.PageResponse;
import com.examly.springapp.model.Order;
import com.examly.springapp.service.OrderService;
import com.examly.springapp.service.AuditService;
import com.examly.springapp.utils.JwtUtil;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;

    public OrderController(OrderService orderService, JwtUtil jwtUtil, AuditService auditService) {
        this.orderService = orderService;
        this.jwtUtil = jwtUtil;
        this.auditService = auditService;
    }

    /**
     * Create order — authenticated users only.
     * userId is extracted from JWT so the frontend cannot spoof it.
     */
    @PostMapping
    public ResponseEntity<Order> createOrder(
            @Valid @RequestBody OrderCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId = extractUserId(authHeader);
        return ResponseEntity.status(201).body(orderService.createOrder(request, userId));
    }

    /** Admin: get all orders */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Order>> getAllOrders() {
        return ResponseEntity.ok(orderService.getAllOrders());
    }

    /** User: get own orders (extracted from JWT) */
    @GetMapping("/my")
    public ResponseEntity<List<Order>> getMyOrders(
            @RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserId(authHeader);
        if (userId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(orderService.getOrdersByUserId(userId));
    }

    /**
     * Phase 10C: admin order list — one page of orders (server-side paging,
     * sorting and filtering). The legacy {@code GET /api/orders} array
     * endpoint is unchanged.
     */
    @GetMapping("/paged")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<OrderListItemDto>> getOrdersPaged(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) Double minAmount,
            @RequestParam(required = false) Double maxAmount) {
        PageRequestParams params = PageRequestParams.parseOrderParams(page, size, sort)
                .withStatus(status)
                .withSearch(search)
                .withDateFrom(dateFrom)
                .withDateTo(dateTo)
                .withMinAmount(minAmount)
                .withMaxAmount(maxAmount);

        return ResponseEntity.ok(orderService.getOrdersPaged(null, params));
    }

    /**
     * Phase 10C: customer order history — one page of the caller's own
     * orders. Ownership is taken from the JWT, never from a request
     * parameter, so a customer can only ever page their own orders.
     */
    @GetMapping("/my/paged")
    public ResponseEntity<PageResponse<OrderListItemDto>> getMyOrdersPaged(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) Double minAmount,
            @RequestParam(required = false) Double maxAmount,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserId(authHeader);
        if (userId == null) return ResponseEntity.status(401).build();

        PageRequestParams params = PageRequestParams.parseOrderParams(page, size, sort)
                .withStatus(status)
                .withSearch(search)
                .withDateFrom(dateFrom)
                .withDateTo(dateTo)
                .withMinAmount(minAmount)
                .withMaxAmount(maxAmount);

        return ResponseEntity.ok(orderService.getOrdersPaged(userId, params));
    }

    /**
     * Phase 10C: aggregate order metrics over the whole table.
     *
     * <p>The admin list is paginated, so the KPI cards can no longer be derived
     * from the rows of the current page — these numbers are computed over
     * every order instead.
     */
    @GetMapping("/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OrderStatsDto> getOrderStats() {
        return ResponseEntity.ok(orderService.getOrderStats());
    }

    /**
     * Phase 10C: CSV export of ALL orders matching the current filters
     * (not just the rows of the current page).
     */
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<StreamingResponseBody> exportOrders(
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) Double minAmount,
            @RequestParam(required = false) Double maxAmount) {
        PageRequestParams params = PageRequestParams.parseOrderParams(null, null, sort)
                .withStatus(status)
                .withSearch(search)
                .withDateFrom(dateFrom)
                .withDateTo(dateTo)
                .withMinAmount(minAmount)
                .withMaxAmount(maxAmount);

        String filename = "orders-export-" + LocalDate.now() + ".csv";
        StreamingResponseBody body = outputStream -> {
            try (java.io.Writer writer = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8))) {
                orderService.writeOrdersCsv(null, params, writer);
            }
        };
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    /** User: get a single own order by ID (ownership verified against JWT userId) */
    @GetMapping("/my/{id}")
    public ResponseEntity<Order> getMyOrderById(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserId(authHeader);
        if (userId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(orderService.getOrderByIdForUser(id, userId));
    }

    /** Admin: get any order by ID */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Order> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderById(id));
    }

    /** Admin: update order status */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Order> updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusUpdateRequest req) {
        Order updated = orderService.updateStatus(id, req);
        auditService.logCurrentUser("ORDER_STATUS_UPDATE", "Order", id, "Status set to " + updated.getStatus());
        return ResponseEntity.ok(updated);
    }

    /** User: cancel own order */
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<Order> cancelMyOrder(
            @PathVariable Long id,
            @RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserId(authHeader);
        Order cancelled = orderService.cancelOrder(id, userId);
        auditService.logCurrentUser("ORDER_CANCEL", "Order", id, "Order cancelled by customer");
        return ResponseEntity.ok(cancelled);
    }

    /** Admin: delete order */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long id) {
        orderService.deleteOrder(id);
        auditService.logCurrentUser("ORDER_DELETE", "Order", id, "Order deleted by admin");
        return ResponseEntity.noContent().build();
    }

    private Long extractUserId(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                return jwtUtil.extractUserId(token);
            }
        }
        return null;
    }
}
