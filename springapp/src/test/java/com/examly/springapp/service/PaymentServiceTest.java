package com.examly.springapp.service;

import com.examly.springapp.model.Order;
import com.examly.springapp.model.Payment;
import com.examly.springapp.model.Product;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.PaymentRepository;
import com.examly.springapp.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * D2 regression tests: a CANCELLED order must not be payable, and an order that already
 * has a SUCCESS payment must not be paid twice. Runs on the in-memory H2 test database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class PaymentServiceTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ObjectMapper objectMapper;

    private Order pendingOrder;

    @BeforeEach
    void setup() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        productRepository.deleteAll();

        Product p = productRepository.save(Product.builder()
                .name("Widget").description("Widget").price(50.0)
                .category("Electronics").stockQuantity(10).build());
        pendingOrder = createOrder("PENDING", 100.0, p);
    }

    private Order createOrder(String status, double total, Product p) {
        Order o = Order.builder()
                .customerName("Pat")
                .customerEmail("pat@example.com")
                .shippingAddress("1 Test St")
                .status(status)
                .orderDate(LocalDateTime.now())
                .totalAmount(total)
                .build();
        o.setOrderItems(new java.util.ArrayList<>(List.of(
                com.examly.springapp.model.OrderItem.builder()
                        .order(o).product(p).quantity(2).priceAtPurchase(50.0).build())));
        return orderRepository.save(o);
    }

    @Test
    void d2_cancelledOrderPaymentRejected() {
        Order cancelled = createOrder("CANCELLED", 100.0,
                productRepository.findAll().get(0));
        long before = paymentRepository.findAll().size();

        ValidationException ex = assertThrows(ValidationException.class,
                () -> paymentService.pay(cancelled.getId(), "MOCK"));
        assertTrue(ex.getMessage().toLowerCase().contains("cancelled"));

        assertEquals(before, paymentRepository.findAll().size(), "no payment must be created");
    }

    @Test
    void d2_duplicateSuccessPaymentRejected() {
        Payment first = paymentService.pay(pendingOrder.getId(), "MOCK");
        assertEquals("SUCCESS", first.getStatus());
        long afterFirst = paymentRepository.findAll().size();

        ValidationException ex = assertThrows(ValidationException.class,
                () -> paymentService.pay(pendingOrder.getId(), "MOCK"));
        assertTrue(ex.getMessage().toLowerCase().contains("already"));

        assertEquals(afterFirst, paymentRepository.findAll().size(), "second payment must not be created");
    }

    @Test
    void d2_normalPaymentStillSucceeds() {
        long before = paymentRepository.findAll().size();
        Payment payment = paymentService.pay(pendingOrder.getId(), "MOCK");

        assertNotNull(payment.getId());
        assertEquals(pendingOrder.getId(), payment.getOrderId());
        assertEquals(100.0, payment.getAmount(), 1e-9);
        assertEquals("SUCCESS", payment.getStatus());
        assertNotNull(payment.getTransactionRef());
        assertNotNull(payment.getInvoiceNumber());
        assertEquals(before + 1, paymentRepository.findAll().size());
    }

    @Test
    void d2_paymentEndpointsAuthorized() throws Exception {
        // unauthenticated -> 401
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("orderId", pendingOrder.getId(), "method", "MOCK"))))
                .andExpect(status().isUnauthorized());
    }
}