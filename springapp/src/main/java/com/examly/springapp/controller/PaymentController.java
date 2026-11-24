package com.examly.springapp.controller;

import com.examly.springapp.dto.PaymentRequest;
import com.examly.springapp.model.Order;
import com.examly.springapp.model.Payment;
import com.examly.springapp.service.OrderService;
import com.examly.springapp.service.PaymentService;
import com.examly.springapp.utils.JwtUtil;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Mock payment + invoice endpoints.
 * Ownership is enforced: a USER may only pay / view invoices for their OWN orders,
 * identified from the JWT (never from a client-supplied userId).
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final OrderService orderService;
    private final JwtUtil jwtUtil;

    @PostMapping
    public ResponseEntity<Payment> pay(@Valid @RequestBody PaymentRequest req,
                                       @RequestHeader("Authorization") String authHeader) {
        Order order = orderService.getOrderById(req.getOrderId());
        enforceOwnership(order, authHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(paymentService.pay(req.getOrderId(), req.getMethod()));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<List<Payment>> byOrder(@PathVariable Long orderId,
                                                 @RequestHeader("Authorization") String authHeader) {
        Order order = orderService.getOrderById(orderId);
        enforceOwnership(order, authHeader);
        return ResponseEntity.ok(paymentService.getPaymentsForOrder(orderId));
    }

    @GetMapping("/invoice/{orderId}")
    public ResponseEntity<Map<String, Object>> invoice(@PathVariable Long orderId,
                                                       @RequestHeader("Authorization") String authHeader) {
        Order order = orderService.getOrderById(orderId);
        enforceOwnership(order, authHeader);
        return ResponseEntity.ok(paymentService.buildInvoice(orderId));
    }

    /** USER may only access their own orders; ADMIN may access any. */
    private void enforceOwnership(Order order, String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        String token = authHeader.substring(7);
        if (!jwtUtil.validateToken(token)) {
            throw new org.springframework.security.access.AccessDeniedException("Invalid token");
        }
        String role = jwtUtil.extractRole(token);
        if ("ADMIN".equalsIgnoreCase(role)) return;
        Long userId = jwtUtil.extractUserId(token);
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("Not your order");
        }
    }
}
