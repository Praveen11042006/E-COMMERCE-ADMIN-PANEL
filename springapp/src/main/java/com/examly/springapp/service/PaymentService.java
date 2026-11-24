package com.examly.springapp.service;

import com.examly.springapp.model.Order;
import com.examly.springapp.model.OrderItem;
import com.examly.springapp.model.Payment;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.PaymentRepository;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Mock payment abstraction for development + invoice generation.
 * NO real financial credentials or external gateways are used.
 * States: PENDING -> SUCCESS | FAILED.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;

    public Payment pay(Long orderId, String method) {
        // The order row is the serialization point for competing payment attempts.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ValidationException("Order not found"));

        // D2: never pay a cancelled order.
        if (order.getStatus() != null && "CANCELLED".equalsIgnoreCase(order.getStatus().trim())) {
            throw new ValidationException("Cannot process payment for a cancelled order");
        }

        // D2: never create a second successful payment for the same order.
        boolean alreadyPaid = paymentRepository.findByOrderId(orderId).stream()
                .anyMatch(p -> "SUCCESS".equalsIgnoreCase(p.getStatus()));
        if (alreadyPaid) {
            throw new ValidationException("Payment already succeeded for this order");
        }

        Payment payment = Payment.builder()
                .orderId(orderId)
                .amount(order.getTotalAmount())
                .method(method == null || method.isBlank() ? "MOCK" : method)
                .status("PENDING")
                .invoiceNumber(generateInvoiceNumber(orderId))
                .createdAt(LocalDateTime.now())
                .build();

        // Mock gateway: deterministic success (no external calls).
        payment.setStatus("SUCCESS");
        payment.setTransactionRef("MOCK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        return paymentRepository.save(payment);
    }

    @Transactional(readOnly = true)
    public List<Payment> getPaymentsForOrder(Long orderId) {
        return paymentRepository.findByOrderId(orderId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> buildInvoice(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ValidationException("Order not found"));

        List<Map<String, Object>> items = new ArrayList<>();
        double subtotal = 0.0;
        if (order.getOrderItems() != null) {
            for (OrderItem it : order.getOrderItems()) {
                double lineTotal = it.getPriceAtPurchase() * it.getQuantity();
                subtotal += lineTotal;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("product", it.getProduct() != null ? it.getProduct().getName() : "Product");
                m.put("quantity", it.getQuantity());
                m.put("unitPrice", round(it.getPriceAtPurchase()));
                m.put("lineTotal", round(lineTotal));
                items.add(m);
            }
        }

        String invoiceNumber = paymentRepository.findByOrderId(orderId).stream()
                .map(Payment::getInvoiceNumber)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(generateInvoiceNumber(orderId));

        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("invoiceNumber", invoiceNumber);
        invoice.put("orderId", order.getId());
        invoice.put("customer", order.getCustomerName());
        invoice.put("customerEmail", order.getCustomerEmail());
        invoice.put("shippingAddress", order.getShippingAddress());
        invoice.put("orderDate", order.getOrderDate() != null ? order.getOrderDate().toString() : "");
        invoice.put("status", order.getStatus());
        invoice.put("currency", "INR");
        invoice.put("items", items);
        invoice.put("subtotal", round(subtotal));
        invoice.put("discount", round(order.getDiscountAmount()));
        invoice.put("couponCode", order.getCouponCode());
        invoice.put("total", round(order.getTotalAmount()));
        return invoice;
    }

    private String generateInvoiceNumber(Long orderId) {
        return "INV-" + LocalDateTime.now().getYear() + "-" + String.format("%05d", orderId);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
