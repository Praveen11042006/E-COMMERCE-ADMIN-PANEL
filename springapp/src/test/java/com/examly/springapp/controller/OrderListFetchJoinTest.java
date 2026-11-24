package com.examly.springapp.controller;

import com.examly.springapp.model.Product;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.ProductRepository;
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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 10B — regression guard for the order-list N+1 fix.
 *
 * <p>The admin and customer order lists now use a fetch-join (orders + items +
 * products in a single query) instead of lazy loading, which serialized with one
 * extra SELECT per order/item (N+1). The JSON the frontend consumes MUST stay
 * byte-for-byte identical, and customer ownership scoping MUST be preserved.
 *
 * <p>These tests assert the observable contract through the public API: the
 * response contains each order's items and product names (the data the N+1 fix
 * must still populate), and a customer only ever sees their own orders. They are
 * deterministic — no wall-clock/timing assertions (see the task's "no fake
 * performance tests" rule).
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class OrderListFetchJoinTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;

    private Product product;
    private String userToken;       // userId = 1L, USER
    private String otherUserToken;  // userId = 2L, USER
    private String adminToken;      // userId = 3L, ADMIN

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        product = productRepository.save(Product.builder()
                .name("Laptop").description("Dev laptop")
                .price(1200.0).category("Electronics").stockQuantity(10).build());
        userToken = "Bearer " + jwtUtil.generateToken("user@test.com", "USER", 1L);
        otherUserToken = "Bearer " + jwtUtil.generateToken("other@test.com", "USER", 2L);
        adminToken = "Bearer " + jwtUtil.generateToken("admin@test.com", "ADMIN", 3L);
    }

    /** Creates one order as the given (authenticated) principal; userId is taken from the JWT. */
    private void createOrderFor(String token, int qty) throws Exception {
        List<Map<String, Object>> items =
                List.of(Map.of("productId", product.getId(), "quantity", qty));
        mockMvc.perform(post("/api/orders")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "customerName", "Cust",
                        "customerEmail", "cust@example.com",
                        "shippingAddress", "1 St",
                        "orderItems", items))))
                .andExpect(status().isCreated());
    }

    @Test
    void myOrders_returnsItemsAndProductNames_populated() throws Exception {
        createOrderFor(userToken, 2); // 2 x 1200 = 2400

        mockMvc.perform(get("/api/orders/my").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                // The fetch-join must still populate items + product details.
                .andExpect(jsonPath("$[0].orderItems", hasSize(1)))
                .andExpect(jsonPath("$[0].orderItems[0].quantity", is(2)))
                .andExpect(jsonPath("$[0].orderItems[0].product.name", is("Laptop")))
                .andExpect(jsonPath("$[0].totalAmount", is(closeTo(2400.0, 0.01))));
    }

    @Test
    void myOrders_isScopedToOwner_doesNotLeakOtherUsersOrders() throws Exception {
        createOrderFor(userToken, 1); // owned by userId = 1L

        // Another authenticated user must see an empty list — never user 1's order.
        mockMvc.perform(get("/api/orders/my").header("Authorization", otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void myOrders_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/orders/my"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminOrderList_returnsItemsAndProductNames_populated() throws Exception {
        createOrderFor(userToken, 3); // 3 x 1200 = 3600

        mockMvc.perform(get("/api/orders").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderItems", hasSize(1)))
                .andExpect(jsonPath("$[0].orderItems[0].product.name", is("Laptop")))
                .andExpect(jsonPath("$[0].totalAmount", is(closeTo(3600.0, 0.01))));
    }

    @Test
    void adminOrderList_isAdminOnly() throws Exception {
        createOrderFor(userToken, 1);

        // A non-admin authenticated user must be rejected from the admin list.
        mockMvc.perform(get("/api/orders").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }
}
