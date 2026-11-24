package com.examly.springapp.controller;

import com.examly.springapp.model.Order;
import com.examly.springapp.model.Payment;
import com.examly.springapp.model.Role;
import com.examly.springapp.model.User;
import com.examly.springapp.repository.CouponRepository;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.PaymentRepository;
import com.examly.springapp.repository.ProductRepository;
import com.examly.springapp.repository.UserRepository;
import com.examly.springapp.utils.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.LocalDateTime;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13D — customers can see the payment state of their OWN orders only.
 *
 * <p>Asserts that:
 * <ul>
 *   <li>{@code /api/orders/my/paged} embeds the latest STORED payment and leaves
 *       {@code payment} null when no record exists;</li>
 *   <li>ownership comes from the JWT — another customer never sees these orders
 *       or their payment fields;</li>
 *   <li>{@code /api/payments/order/{id}} is owner-scoped (IDOR guard).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class PaymentVisibilityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String aliceToken;
    private String bobToken;
    private String adminToken;
    private Long paidOrderId;
    private Long unpaidOrderId;
    private Long bobOrderId;

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        couponRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        User alice = saveUser("alice@pay.test", Role.USER);
        User bob = saveUser("bob@pay.test", Role.USER);
        User admin = saveUser("admin@pay.test", Role.ADMIN);

        aliceToken = "Bearer " + jwtUtil.generateToken(alice.getEmail(), "USER", alice.getId());
        bobToken = "Bearer " + jwtUtil.generateToken(bob.getEmail(), "USER", bob.getId());
        adminToken = "Bearer " + jwtUtil.generateToken(admin.getEmail(), "ADMIN", admin.getId());

        paidOrderId = orderRepository.save(orderFor(alice.getId(), 300.0)).getId();
        unpaidOrderId = orderRepository.save(orderFor(alice.getId(), 120.0)).getId();
        bobOrderId = orderRepository.save(orderFor(bob.getId(), 90.0)).getId();

        // Older failed attempt, then the successful payment -> latest must win.
        paymentRepository.save(Payment.builder()
                .orderId(paidOrderId).amount(300.0).method("MOCK").status("FAILED")
                .createdAt(LocalDateTime.now().minusMinutes(5)).build());
        paymentRepository.save(Payment.builder()
                .orderId(paidOrderId).amount(300.0).method("MOCK").status("SUCCESS")
                .transactionRef("MOCK-ABC123").invoiceNumber("INV-2026-00001")
                .createdAt(LocalDateTime.now()).build());

        // Bob paid his own order; Alice must never see it.
        paymentRepository.save(Payment.builder()
                .orderId(bobOrderId).amount(90.0).method("MOCK").status("PENDING")
                .createdAt(LocalDateTime.now()).build());
    }

    private User saveUser(String email, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode("Password@1"));
        u.setName(email.split("@")[0]);
        u.setRole(role);
        u.setActive(true);
        u.setCreatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    private Order orderFor(Long userId, double total) {
        return Order.builder()
                .customerName("Customer " + userId)
                .customerEmail("cust" + userId + "@pay.test")
                .shippingAddress("1 Test St")
                .status("PENDING")
                .orderDate(LocalDateTime.now())
                .userId(userId)
                .orderItems(new ArrayList<>())
                .totalAmount(total)
                .discountAmount(0.0)
                .build();
    }

    private JsonNode myOrders(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/orders/my/paged")
                        .param("size", "10")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void customerSeesLatestStoredPaymentAndNullWhenMissing() throws Exception {
        JsonNode page = myOrders(aliceToken);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);

        JsonNode paid = null;
        JsonNode unpaid = null;
        for (JsonNode order : page.get("content")) {
            if (order.get("id").asLong() == paidOrderId) paid = order;
            if (order.get("id").asLong() == unpaidOrderId) unpaid = order;
        }

        assertThat(paid).isNotNull();
        assertThat(paid.get("payment").get("status").asText()).isEqualTo("SUCCESS");
        assertThat(paid.get("payment").get("method").asText()).isEqualTo("MOCK");
        assertThat(paid.get("payment").get("amount").asDouble()).isEqualTo(300.0);
        assertThat(paid.get("payment").get("transactionRef").asText()).isEqualTo("MOCK-ABC123");
        assertThat(paid.get("payment").get("invoiceNumber").asText()).isEqualTo("INV-2026-00001");

        assertThat(unpaid).isNotNull();
        assertThat(unpaid.get("payment").isNull()).isTrue();
    }

    @Test
    void customerPageNeverContainsAnotherCustomersOrdersOrPayments() throws Exception {
        JsonNode page = myOrders(bobToken);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);

        JsonNode only = page.get("content").get(0);
        assertThat(only.get("id").asLong()).isEqualTo(bobOrderId);
        assertThat(only.get("payment").get("status").asText()).isEqualTo("PENDING");

        String body = page.toString();
        assertThat(body).doesNotContain("MOCK-ABC123");
        assertThat(body).doesNotContain("INV-2026-00001");
    }

    @Test
    void adminOrderListAndCsvExportKeepTheirContract() throws Exception {
        MvcResult adminPage = mockMvc.perform(get("/api/orders/paged")
                        .param("size", "10")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andReturn();

        JsonNode page = objectMapper.readTree(adminPage.getResponse().getContentAsString());
        for (JsonNode order : page.get("content")) {
            if (order.get("id").asLong() == paidOrderId) {
                assertThat(order.get("payment").get("status").asText()).isEqualTo("SUCCESS");
            }
        }

        // Streaming CSV export: same columns, one line per order, and no payment
        // data leaking into the file.
        MvcResult csvResult = mockMvc.perform(get("/api/orders/export")
                        .header("Authorization", adminToken))
                .andExpect(request().asyncStarted())
                .andReturn();
        csvResult.getAsyncResult();
        String csv = csvResult.getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo(
                "Order ID,Customer Name,Email,Phone,Status,Total Amount,Order Date,Shipping Address,Items Count");
        assertThat(csv).doesNotContain("MOCK-ABC123");
        assertThat(csv).doesNotContain("INV-2026-00001");

        // The legacy array endpoint is unchanged.
        mockMvc.perform(MockMvcRequestBuilders.get("/api/orders")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void paymentRecordsAreOwnerScoped() throws Exception {
        mockMvc.perform(get("/api/payments/order/" + paidOrderId).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        mockMvc.perform(get("/api/payments/order/" + paidOrderId).header("Authorization", bobToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/payments/order/" + paidOrderId))
                .andExpect(status().isUnauthorized());

        // ADMIN keeps read access to any order.
        mockMvc.perform(get("/api/payments/order/" + paidOrderId).header("Authorization", adminToken))
                .andExpect(status().isOk());
    }
}
