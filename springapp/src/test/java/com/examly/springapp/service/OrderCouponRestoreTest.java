package com.examly.springapp.service;

import com.examly.springapp.dto.OrderCreateRequest;
import com.examly.springapp.dto.OrderItemCreateRequest;
import com.examly.springapp.dto.OrderStatusUpdateRequest;
import com.examly.springapp.model.Coupon;
import com.examly.springapp.model.Order;
import com.examly.springapp.model.Product;
import com.examly.springapp.repository.CouponRepository;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.PaymentRepository;
import com.examly.springapp.repository.ProductRepository;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9 regression tests for coupon usage accounting:
 *  - order creation with a coupon increments usedCount
 *  - cancelling that order restores usedCount
 *  - a repeated cancellation attempt does NOT restore twice
 *  - usedCount can never become negative
 *  - admin status-based cancellation restores usage as well
 * Runs on the in-memory H2 test database.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class OrderCouponRestoreTest {

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private PaymentRepository paymentRepository;

    private Product product;
    private Coupon coupon;
    private final Long userId = 77L;

    @BeforeEach
    void setup() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();
        couponRepository.deleteAll();

        product = productRepository.save(Product.builder()
                .name("Gadget").description("Gadget").price(100.0)
                .category("Electronics").stockQuantity(10).build());
        coupon = couponRepository.save(Coupon.builder()
                .code("SAVE10").discountType("PERCENTAGE").discountValue(10)
                .minOrderAmount(0).maxDiscount(0).usageLimit(0).usedCount(0)
                .active(true).build());
    }

    private Order createOrderWithCoupon() {
        return orderService.createOrder(OrderCreateRequest.builder()
                .customerName("Cara")
                .customerEmail("cara@example.com")
                .shippingAddress("1 Coupon St")
                .orderItems(List.of(new OrderItemCreateRequest(product.getId(), 1)))
                .couponCode("SAVE10")
                .build(), userId);
    }

    private int usedCount() {
        return couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount();
    }

    @Test
    void orderCreationIncrementsCouponUsage() {
        Order order = createOrderWithCoupon();

        assertNotNull(order.getId());
        assertEquals(1, usedCount(), "creating an order with a coupon must increment usedCount");
        assertEquals(10.0, order.getDiscountAmount(), 1e-9);
        assertEquals(90.0, order.getTotalAmount(), 1e-9);
    }

    @Test
    void cancelRestoresCouponUsage() {
        Order order = createOrderWithCoupon();
        assertEquals(1, usedCount());

        Order cancelled = orderService.cancelOrder(order.getId(), userId);

        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals(0, usedCount(), "cancelling must restore one coupon usage");
        // Stock is restored alongside (existing invariant preserved).
        assertEquals(10, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
    }

    @Test
    void repeatedCancellationDoesNotRestoreTwice() {
        Order order = createOrderWithCoupon();
        orderService.cancelOrder(order.getId(), userId);
        assertEquals(0, usedCount());

        assertThrows(ValidationException.class,
                () -> orderService.cancelOrder(order.getId(), userId),
                "second cancellation must be rejected");

        assertEquals(0, usedCount(), "repeated cancellation must not restore usage twice");
    }

    @Test
    void usageNeverBecomesNegative() {
        // Legacy-data scenario: order carries a coupon code but usage was never incremented.
        Order legacy = orderRepository.save(Order.builder()
                .customerName("Legacy")
                .customerEmail("legacy@example.com")
                .shippingAddress("1 Old St")
                .status("PENDING")
                .userId(userId)
                .orderItems(new ArrayList<>())
                .totalAmount(100.0)
                .couponCode("SAVE10")
                .discountAmount(0.0)
                .build());
        assertEquals(0, usedCount());

        orderService.cancelOrder(legacy.getId(), userId);

        assertEquals(0, usedCount(), "usedCount must never drop below zero");
    }

    @Test
    void adminStatusCancelAlsoRestoresUsage() {
        Order order = createOrderWithCoupon();
        assertEquals(1, usedCount());

        orderService.updateStatus(order.getId(), new OrderStatusUpdateRequest("CANCELLED"));

        assertEquals(0, usedCount(), "admin status change to CANCELLED must restore usage too");
    }

    @Test
    void orderWithoutCouponDoesNotTouchUsage() {
        Order order = orderService.createOrder(OrderCreateRequest.builder()
                .customerName("NoCoupon")
                .customerEmail("noc@example.com")
                .shippingAddress("2 Plain St")
                .orderItems(List.of(new OrderItemCreateRequest(product.getId(), 1)))
                .build(), userId);
        assertEquals(0, usedCount());

        orderService.cancelOrder(order.getId(), userId);

        assertEquals(0, usedCount());
    }
}
