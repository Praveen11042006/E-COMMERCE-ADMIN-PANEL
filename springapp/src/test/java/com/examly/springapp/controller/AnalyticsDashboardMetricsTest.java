package com.examly.springapp.controller;

import com.examly.springapp.model.Product;
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
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13E — the dashboard no longer downloads the order table.
 *
 * <p>Guards the aggregate contract of {@code /api/analytics/order-metrics}
 * (totals, status counts, top products/customers, recent orders, conversion
 * metrics) and the three-column {@code /api/analytics/time-series} projection,
 * plus their date window and admin-only authorization.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class AnalyticsDashboardMetricsTest {

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

        adminToken = "Bearer " + jwtUtil.generateToken("admin@dash.test", "ADMIN", 1L);
        userToken = "Bearer " + jwtUtil.generateToken("user@dash.test", "USER", 2L);

        laptop = productRepository.save(Product.builder()
                .name("Laptop").description("Dev laptop").price(100.0)
                .category("Electronics").stockQuantity(50).build());
        cable = productRepository.save(Product.builder()
                .name("Cable").description("USB cable").price(40.0)
                .category("Accessories").stockQuantity(50).build());

        createOrder("alice@dash.test", laptop.getId(), 2);  // 200
        createOrder("alice@dash.test", cable.getId(), 1);   // 40
        createOrder("bob@dash.test", laptop.getId(), 1);    // 100
    }

    private void createOrder(String email, Long productId, int qty) throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerName", email.split("@")[0],
                                "customerEmail", email,
                                "shippingAddress", "1 St",
                                "orderItems", List.of(Map.of("productId", productId, "quantity", qty))))))
                .andExpect(status().isCreated());
    }

    private JsonNode metrics(Long from, Long to) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/analytics/order-metrics")
                        .param("from", from == null ? "" : String.valueOf(from))
                        .param("to", to == null ? "" : String.valueOf(to))
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void totalsAndConversionMetricsComeFromTheDatabase() throws Exception {
        JsonNode m = metrics(null, null);

        assertThat(m.get("orders").asLong()).isEqualTo(3);
        assertThat(m.get("revenue").asDouble()).isEqualTo(340.0);
        assertThat(m.get("customers").asLong()).isEqualTo(2);
        assertThat(m.get("averageOrderValue").asDouble()).isCloseTo(340.0 / 3, org.assertj.core.data.Offset.offset(0.001));
        // Alice ordered twice, Bob once.
        assertThat(m.get("repeatCustomers").asLong()).isEqualTo(1);
        assertThat(m.get("avgOrdersPerCustomer").asDouble()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.001));
        assertThat(m.get("customerLifetimeValue").asDouble()).isCloseTo(170.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void tablesAndStatusChartAreAggregated() throws Exception {
        JsonNode m = metrics(null, null);

        assertThat(m.get("statusDistribution")).hasSize(1);
        assertThat(m.get("statusDistribution").get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(m.get("statusDistribution").get(0).get("count").asLong()).isEqualTo(3);

        assertThat(m.get("topProducts")).hasSize(2);
        assertThat(m.get("topProducts").get(0).get("name").asText()).isEqualTo("Laptop");
        assertThat(m.get("topProducts").get(0).get("quantity").asLong()).isEqualTo(3);
        assertThat(m.get("topProducts").get(0).get("revenue").asDouble()).isEqualTo(300.0);

        assertThat(m.get("topCustomers")).hasSize(2);
        assertThat(m.get("topCustomers").get(0).get("email").asText()).isEqualTo("alice@dash.test");
        assertThat(m.get("topCustomers").get(0).get("totalOrders").asLong()).isEqualTo(2);
        assertThat(m.get("topCustomers").get(0).get("totalSpent").asDouble()).isEqualTo(240.0);

        assertThat(m.get("recentOrders")).hasSize(3);
        assertThat(m.get("recentOrders").get(0).get("itemCount").asLong()).isEqualTo(1);
    }

    @Test
    void timeSeriesExposesOnlyTheThreeNeededColumns() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/analytics/time-series")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode rows = objectMapper.readTree(result.getResponse().getContentAsString());

        assertThat(rows).hasSize(3);
        JsonNode first = rows.get(0);
        assertThat(first.has("orderDate")).isTrue();
        assertThat(first.has("totalAmount")).isTrue();
        assertThat(first.has("customerEmail")).isTrue();
        assertThat(first.has("customerName")).isFalse();
        assertThat(first.has("orderItems")).isFalse();
        assertThat(first.has("id")).isFalse();
    }

    @Test
    void dateWindowRestrictsEveryAggregate() throws Exception {
        long futureFrom = Instant.now().plusSeconds(86_400).toEpochMilli();

        JsonNode m = metrics(futureFrom, futureFrom + 86_400_000L);
        assertThat(m.get("orders").asLong()).isZero();
        assertThat(m.get("revenue").asDouble()).isZero();
        assertThat(m.get("customers").asLong()).isZero();
        assertThat(m.get("topProducts")).isEmpty();
        assertThat(m.get("topCustomers")).isEmpty();
        assertThat(m.get("recentOrders")).isEmpty();
        assertThat(m.get("statusDistribution")).isEmpty();

        mockMvc.perform(get("/api/analytics/time-series")
                        .param("from", String.valueOf(futureFrom))
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void dashboardEndpointsRemainAdminOnly() throws Exception {
        mockMvc.perform(get("/api/analytics/order-metrics").header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/time-series").header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/analytics/order-metrics"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/analytics/time-series"))
                .andExpect(status().isUnauthorized());
    }
}
