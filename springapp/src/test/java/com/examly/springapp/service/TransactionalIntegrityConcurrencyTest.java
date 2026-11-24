package com.examly.springapp.service;

import com.examly.springapp.dto.OrderCreateRequest;
import com.examly.springapp.dto.OrderItemCreateRequest;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * H2 regression coverage for transaction boundaries and pessimistic-lock paths.
 * MySQL locking semantics still require staging verification before production use.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class TransactionalIntegrityConcurrencyTest {

    @Autowired private OrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private ProductRepository productRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;

    private Product product;

    @BeforeEach
    void setup() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        couponRepository.deleteAll();
        productRepository.deleteAll();
        product = productRepository.save(Product.builder().name("Final item").description("Test")
                .price(50).category("Test").stockQuantity(1).build());
    }

    @Test
    void concurrentCheckoutsCannotOversellFinalStock() throws Exception {
        List<Object> results = race(() -> createOrder(null), () -> createOrder(null));

        assertEquals(1, results.stream().filter(Order.class::isInstance).count());
        assertEquals(1, results.stream().filter(ValidationException.class::isInstance).count());
        assertEquals(0, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
        assertEquals(1, orderRepository.count());
    }

    @Test
    void successfulCheckoutStillCreatesOrderAndConsumesStock() {
        Order order = createOrder(null);

        assertNotNull(order.getId());
        assertEquals(0, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
        assertEquals(1, orderRepository.count());
    }

    @Test
    void concurrentPaymentsCreateOneSuccessfulPayment() throws Exception {
        Order order = createOrder(null);
        List<Object> results = race(() -> paymentService.pay(order.getId(), "MOCK"),
                () -> paymentService.pay(order.getId(), "MOCK"));

        assertEquals(1, results.stream().filter(o -> o instanceof com.examly.springapp.model.Payment).count());
        assertEquals(1, results.stream().filter(ValidationException.class::isInstance).count());
        assertEquals(1, paymentRepository.findByOrderId(order.getId()).size());
    }

    @Test
    void concurrentCouponUseCannotExceedFinalUsage() throws Exception {
        product.setStockQuantity(2);
        productRepository.save(product);
        Coupon coupon = couponRepository.save(Coupon.builder().code("FINALUSE").discountType("FIXED")
                .discountValue(5).minOrderAmount(0).maxDiscount(0).usageLimit(1).usedCount(0).active(true).build());

        List<Object> results = race(() -> createOrder("FINALUSE"), () -> createOrder("FINALUSE"));

        assertEquals(2, results.stream().filter(Order.class::isInstance).count());
        assertEquals(1, couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount());
    }

    @Test
    void failedCheckoutRollsBackStockAndCouponUsage() {
        Coupon coupon = couponRepository.save(Coupon.builder().code("ROLLBACK").discountType("FIXED")
                .discountValue(5).minOrderAmount(0).maxDiscount(0).usageLimit(1).usedCount(0).active(true).build());

        assertThrows(ValidationException.class, () ->
            orderService.createOrder(OrderCreateRequest.builder().customerName("Test").customerEmail("test@example.com")
                    .shippingAddress("Address").couponCode("ROLLBACK")
                    .orderItems(List.of(new OrderItemCreateRequest(product.getId(), 1),
                            new OrderItemCreateRequest(999999L, 1))).build(), 1L));

        assertEquals(1, productRepository.findById(product.getId()).orElseThrow().getStockQuantity());
        assertEquals(0, couponRepository.findById(coupon.getId()).orElseThrow().getUsedCount());
        assertEquals(0, orderRepository.count());
    }

    private Order createOrder(String couponCode) {
        Order order = orderService.createOrder(OrderCreateRequest.builder().customerName("Test")
                .customerEmail("test@example.com").shippingAddress("Address").couponCode(couponCode)
                .orderItems(List.of(new OrderItemCreateRequest(product.getId(), 1))).build(), 1L);
        assertNotNull(order.getId());
        return order;
    }

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<Object> coordinated = () -> {
                ready.countDown();
                start.await();
                try {
                    return first.call();
                } catch (Exception e) {
                    return unwrap(e);
                }
            };
            Future<Object> one = pool.submit(coordinated);
            Future<Object> two = pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    return second.call();
                } catch (Exception e) {
                    return unwrap(e);
                }
            });
            ready.await();
            start.countDown();
            return List.of(one.get(), two.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private Object unwrap(Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause instanceof ValidationException ? cause : exception;
    }
}
