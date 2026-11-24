package com.examly.springapp.controller;

import com.examly.springapp.model.Product;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13D — the dashboard charts are aggregated by the database.
 *
 * <p>Regression guard for the two new admin endpoints:
 * <ul>
 *   <li>{@code /api/analytics/category-sales} groups {@code priceAtPurchase * quantity}
 *       by product category and honours the date window;</li>
 *   <li>{@code /api/analytics/inventory} returns the product COUNT plus only the
 *       five lowest-stock products (no catalogue is loaded);</li>
 *   <li>both stay admin-only.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class AnalyticsAggregationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private CouponRepository couponRepository;
    @Autowired private UserRepository userRepository;

    private String adminToken;
    private String userToken;
    private Product laptop;
    private Product cable;

    @BeforeEach
    void setUp() throws Exception {
        paymentRepository.deleteAll();
        orderRepository.deleteAll();
        couponRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        adminToken = "Bearer " + jwtUtil.generateToken("admin@analytics.test", "ADMIN", 1L);
        userToken = "Bearer " + jwtUtil.generateToken("user@analytics.test", "USER", 2L);

        laptop = productRepository.save(Product.builder()
                .name("Laptop").description("Dev laptop").price(100.0)
                .category("Electronics").stockQuantity(50).build());
        cable = productRepository.save(Product.builder()
                .name("Cable").description("USB cable").price(40.0)
                .category("Accessories").stockQuantity(3).build());

        // One order: 2 laptops (Electronics, 200) + 1 cable (Accessories, 40).
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerName", "Cust",
                                "customerEmail", "cust@analytics.test",
                                "shippingAddress", "1 St",
                                "orderItems", List.of(
                                        Map.of("productId", laptop.getId(), "quantity", 2),
                                        Map.of("productId", cable.getId(), "quantity", 1))))))
                .andExpect(status().isCreated());
    }

    @Test
    void categorySalesAggregatesRevenueAndQuantityByCategory() throws Exception {
        mockMvc.perform(get("/api/analytics/category-sales").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].category").value("Electronics"))
                .andExpect(jsonPath("$[0].revenue").value(200.0))
                .andExpect(jsonPath("$[0].quantity").value(2))
                .andExpect(jsonPath("$[1].category").value("Accessories"))
                .andExpect(jsonPath("$[1].revenue").value(40.0))
                .andExpect(jsonPath("$[1].quantity").value(1));
    }

    @Test
    void categorySalesHonoursDateWindow() throws Exception {
        long futureFrom = Instant.now().plusSeconds(86_400).toEpochMilli();
        long futureTo = futureFrom + 86_400_000L;

        mockMvc.perform(get("/api/analytics/category-sales")
                        .param("from", String.valueOf(futureFrom))
                        .param("to", String.valueOf(futureTo))
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void inventoryReturnsCountAndOnlyLowestStockProducts() throws Exception {
        mockMvc.perform(get("/api/analytics/inventory").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(2))
                .andExpect(jsonPath("$.lowStockProducts.length()").value(1))
                // stock was decremented by the order: 3 -> 2
                .andExpect(jsonPath("$.lowStockProducts[0].name").value("Cable"))
                .andExpect(jsonPath("$.lowStockProducts[0].stockQuantity").value(2))
                .andExpect(jsonPath("$.lowStockProducts[0].price").value(40.0));
    }

    @Test
    void cancelledOrdersStayInCategorySalesAsBefore() throws Exception {
        Long orderId = orderRepository.findAll().get(0).getId();
        mockMvc.perform(patch("/api/orders/" + orderId + "/status")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "CANCELLED"))))
                .andExpect(status().isOk());

        // The pre-13D chart aggregated every order, cancelled included; the
        // database-side aggregation preserves that (and returns stock).
        mockMvc.perform(get("/api/analytics/category-sales").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("Electronics"))
                .andExpect(jsonPath("$[0].revenue").value(200.0));
    }

    @Test
    void emptyDatasetReturnsEmptyAggregates() throws Exception {
        orderRepository.deleteAll();
        productRepository.deleteAll();

        mockMvc.perform(get("/api/analytics/inventory").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(0))
                .andExpect(jsonPath("$.lowStockProducts.length()").value(0));

        mockMvc.perform(get("/api/analytics/category-sales").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void analyticsEndpointsRemainAdminOnly() throws Exception {
        mockMvc.perform(get("/api/analytics/category-sales").header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/inventory").header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/category-sales"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/analytics/inventory"))
                .andExpect(status().isUnauthorized());
    }
}
