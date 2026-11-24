package com.examly.springapp.controller;

import com.examly.springapp.model.Coupon;
import com.examly.springapp.model.Order;
import com.examly.springapp.model.Role;
import com.examly.springapp.model.User;
import com.examly.springapp.repository.CouponRepository;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.PaymentRepository;
import com.examly.springapp.repository.ProductRepository;
import com.examly.springapp.repository.UserRepository;
import com.examly.springapp.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 7 checkout authorization matrix:
 *  - CUSTOMER cannot create/list/delete coupons (ADMIN only)
 *  - CUSTOMER cannot pay another user's order (IDOR)
 *  - CUSTOMER can only retrieve their OWN invoice; others get 403; unauthenticated gets 401
 *  - coupon values on the invoice come from stored order data
 * Runs on the in-memory H2 test database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class CheckoutAuthorizationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;

    private String userToken;
    private String otherUserToken;
    private String adminToken;
    private Long aliceOrderId;

    @BeforeEach
    void setup() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        couponRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        User admin = saveUser("admin@chk.com", "Admin@123", Role.ADMIN);
        User alice = saveUser("alice@chk.com", "Alice@123", Role.USER);
        User bob = saveUser("bob@chk.com", "Bobby@123", Role.USER);

        adminToken = "Bearer " + jwtUtil.generateToken(admin.getEmail(), "ADMIN", admin.getId());
        userToken = "Bearer " + jwtUtil.generateToken(alice.getEmail(), "USER", alice.getId());
        otherUserToken = "Bearer " + jwtUtil.generateToken(bob.getEmail(), "USER", bob.getId());

        // Alice's order: total 500 with a stored 50 discount via coupon TEST50.
        couponRepository.save(Coupon.builder()
                .code("TEST50").discountType("PERCENTAGE").discountValue(10)
                .minOrderAmount(0).maxDiscount(0).usageLimit(0).usedCount(0)
                .active(true).build());
        Order order = Order.builder()
                .customerName("Alice")
                .customerEmail("alice@chk.com")
                .shippingAddress("1 Alice St")
                .status("PENDING")
                .orderDate(LocalDateTime.now())
                .userId(alice.getId())
                .orderItems(new java.util.ArrayList<>())
                .totalAmount(500.0)
                .discountAmount(50.0)
                .couponCode("TEST50")
                .build();
        aliceOrderId = orderRepository.save(order).getId();
    }

    private User saveUser(String email, String rawPw, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(rawPw));
        u.setName(email.split("@")[0]);
        u.setRole(role);
        u.setActive(true);
        u.setCreatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    @Test
    void customerCannotCreateCoupon() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "code", "HACK10", "discountType", "PERCENTAGE",
                "discountValue", 100, "minOrderAmount", 0, "active", true));
        mockMvc.perform(post("/api/coupons").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/coupons").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotListOrDeleteCoupons() throws Exception {
        Long couponId = couponRepository.findByCodeIgnoreCase("TEST50").orElseThrow().getId();
        mockMvc.perform(get("/api/coupons").header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/coupons/" + couponId).header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerCannotPayAnotherUsersOrder() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("orderId", aliceOrderId, "method", "MOCK"));

        mockMvc.perform(post("/api/payments").header("Authorization", otherUserToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        // No payment created for the foreign order.
        org.junit.jupiter.api.Assertions.assertEquals(
                0, paymentRepository.findByOrderId(aliceOrderId).size());
    }

    @Test
    void invoiceOnlyAccessibleByOwnerOrAdmin() throws Exception {
        // Owner sees their own invoice, values from stored order data.
        mockMvc.perform(get("/api/payments/invoice/" + aliceOrderId).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(aliceOrderId))
                .andExpect(jsonPath("$.total").value(500.0))
                .andExpect(jsonPath("$.discount").value(50.0))
                .andExpect(jsonPath("$.couponCode").value("TEST50"));

        // Another customer is rejected.
        mockMvc.perform(get("/api/payments/invoice/" + aliceOrderId).header("Authorization", otherUserToken))
                .andExpect(status().isForbidden());

        // Unauthenticated is rejected.
        mockMvc.perform(get("/api/payments/invoice/" + aliceOrderId))
                .andExpect(status().isUnauthorized());

        // ADMIN retains intended access.
        mockMvc.perform(get("/api/payments/invoice/" + aliceOrderId).header("Authorization", adminToken))
                .andExpect(status().isOk());
    }
}