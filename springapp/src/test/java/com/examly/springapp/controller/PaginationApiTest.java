package com.examly.springapp.controller;

import com.examly.springapp.model.Product;
import com.examly.springapp.model.Role;
import com.examly.springapp.model.User;
import com.examly.springapp.repository.OrderRepository;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 10C — regression guard for the server-side pagination / DTO endpoints.
 *
 * <p>Asserts the observable contract of the NEW paginated endpoints:
 * <ul>
 *   <li>the pagination envelope shape (content/page/size/totalElements/…);</li>
 *   <li>that the pages really split the data set (no overlap, no gaps);</li>
 *   <li>that filtering/sorting happen on the server and that
 *       {@code totalElements} reflects the filter;</li>
 *   <li>that authorization and ownership are still enforced;</li>
 *   <li>that the LEGACY array endpoints keep returning arrays (compatibility).</li>
 * </ul>
 *
 * <p>No wall-clock/timing assertions — everything is deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class PaginationApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;

    private Product product;
    private String userToken;      // userId = 1L, USER
    private String otherUserToken; // userId = 2L, USER
    private String adminToken;     // userId = 3L, ADMIN

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        product = productRepository.save(Product.builder()
                .name("Laptop").description("Dev laptop")
                .price(100.0).category("Electronics").stockQuantity(100).build());
        userToken = "Bearer " + jwtUtil.generateToken("user@test.com", "USER", 1L);
        otherUserToken = "Bearer " + jwtUtil.generateToken("other@test.com", "USER", 2L);
        adminToken = "Bearer " + jwtUtil.generateToken("admin@test.com", "ADMIN", 3L);
    }

    /** Creates one order as the given (authenticated) principal; userId comes from the JWT. */
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
    void pagedOrders_returnsEnvelopeShape() throws Exception {
        createOrderFor(userToken, 1);

        mockMvc.perform(get("/api/orders/paged").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", instanceOf(List.class)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page", is(0)))
                .andExpect(jsonPath("$.size", is(10)))
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.totalPages", is(1)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(true)));
    }

    @Test
    void pagedOrders_splitsDataSetAcrossPages_withoutOverlapOrGaps() throws Exception {
        for (int i = 0; i < 7; i++) {
            createOrderFor(userToken, 1);
        }

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("size", "3").param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.totalElements", is(7)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.first", is(true)))
                .andExpect(jsonPath("$.last", is(false)));

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("size", "3").param("page", "1").param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.first", is(false)));

        // The last page holds the remaining single row.
        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("size", "3").param("page", "2").param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.last", is(true)));
    }

    @Test
    void pagedOrders_appliesStatusFilterAndReportsFilteredTotal() throws Exception {
        createOrderFor(userToken, 1);
        createOrderFor(userToken, 2);

        // Move the newest order to SHIPPED via the admin status endpoint.
        long shippedId = orderRepository.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .findFirst().orElseThrow().getId();
        mockMvc.perform(patch("/api/orders/{id}/status", shippedId)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SHIPPED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].status", is("SHIPPED")))
                // The total reflects the filter, not the whole table.
                .andExpect(jsonPath("$.totalElements", is(1)));

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void pagedOrders_appliesSearchAndAmountFilter() throws Exception {
        createOrderFor(userToken, 2); // 2 x 100 = 200

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("search", "cust@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements", is(1)));

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("search", "no-such-customer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)));

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("minAmount", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("minAmount", "100").param("maxAmount", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void pagedOrders_sortsByTotalAmount() throws Exception {
        createOrderFor(userToken, 1); // 100
        createOrderFor(userToken, 3); // 300

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("sort", "totalAmount,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].totalAmount", is(closeTo(100.0, 0.01))))
                .andExpect(jsonPath("$.content[1].totalAmount", is(closeTo(300.0, 0.01))));

        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("sort", "totalAmount,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].totalAmount", is(closeTo(300.0, 0.01))))
                .andExpect(jsonPath("$.content[1].totalAmount", is(closeTo(100.0, 0.01))));
    }

    @Test
    void pagedOrders_returnsProjectedOrderWithItems() throws Exception {
        createOrderFor(userToken, 2);

        mockMvc.perform(get("/api/orders/paged").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].customerName", is("Cust")))
                .andExpect(jsonPath("$.content[0].itemCount", is(1)))
                .andExpect(jsonPath("$.content[0].items[0].quantity", is(2)))
                .andExpect(jsonPath("$.content[0].items[0].productName", is("Laptop")))
                .andExpect(jsonPath("$.content[0].items[0].priceAtPurchase", is(closeTo(100.0, 0.01))))
                .andExpect(jsonPath("$.content[0].items[0].subtotal", is(closeTo(200.0, 0.01))))
                // The owning user id is internal metadata and is not exposed.
                .andExpect(jsonPath("$.content[0].userId").doesNotExist());
    }

    @Test
    void pagedOrders_treatsWildcardSearchLiterally() throws Exception {
        createOrderFor(userToken, 1);

        // A '%' in the search term must NOT match everything — it is escaped.
        mockMvc.perform(get("/api/orders/paged")
                        .header("Authorization", adminToken)
                        .param("search", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements", is(0)));
    }

    @Test
    void pagedOrders_isAdminOnly() throws Exception {
        mockMvc.perform(get("/api/orders/paged"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/orders/paged").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void pagedEndpoints_rejectInvalidQueryParameters() throws Exception {
        mockMvc.perform(get("/api/orders/paged").header("Authorization", adminToken)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/orders/paged").header("Authorization", adminToken)
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/orders/paged").header("Authorization", adminToken)
                        .param("sort", "password,asc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/orders/paged").header("Authorization", adminToken)
                        .param("dateFrom", "not-a-date"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/orders/paged").header("Authorization", adminToken)
                        .param("minAmount", "20").param("maxAmount", "10"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/users/paged").header("Authorization", adminToken)
                        .param("role", "MANAGER"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void myPagedOrders_isScopedToOwner() throws Exception {
        createOrderFor(userToken, 1);       // owned by userId = 1L
        createOrderFor(otherUserToken, 1);  // owned by userId = 2L

        mockMvc.perform(get("/api/orders/my/paged").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                // The total is owner-scoped too.
                .andExpect(jsonPath("$.totalElements", is(1)));

        mockMvc.perform(get("/api/orders/my/paged").header("Authorization", otherUserToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void myPagedOrders_requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/orders/my/paged"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void orderStats_reportsFullDatasetNotJustThePage() throws Exception {
        for (int i = 0; i < 12; i++) {
            createOrderFor(userToken, 1); // 12 x 100 = 1200
        }

        mockMvc.perform(get("/api/orders/stats").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(12)))
                .andExpect(jsonPath("$.pending", is(12)))
                .andExpect(jsonPath("$.totalRevenue", is(closeTo(1200.0, 0.01))))
                .andExpect(jsonPath("$.averageOrderValue", is(closeTo(100.0, 0.01))));
    }

    @Test
    void orderStats_isAdminOnly() throws Exception {
        mockMvc.perform(get("/api/orders/stats").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void exportOrders_returnsCsvOfAllMatchingRows() throws Exception {
        for (int i = 0; i < 3; i++) {
            createOrderFor(userToken, 1);
        }

        MvcResult result = mockMvc.perform(get("/api/orders/export").header("Authorization", adminToken))
                .andExpect(request().asyncStarted())
                .andReturn();
        // StreamingResponseBody runs asynchronously. Waiting on the result
        // exercises the same response without issuing a second (uncredentialed)
        // mock request.
        result.getAsyncResult();
        String csv = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getContentType()).contains("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("orders-export-");

        // One header line + one line per matching order.
        assertThat(csv.split("\n")).hasSize(4);
        assertThat(csv).contains("Order ID,Customer Name");
    }

    @Test
    void exportOrders_isAdminOnly() throws Exception {
        mockMvc.perform(get("/api/orders/export"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/export").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void pagedUsers_returnsEnvelopeAndNeverExposesPassword() throws Exception {
        userRepository.deleteAll();
        userRepository.save(new User(
                "admin@test.com", "secret", "Admin User",
                Role.ADMIN));

        mockMvc.perform(get("/api/users/paged").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", instanceOf(List.class)))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.totalElements", is(1)))
                // The password hash must never be part of the list payload.
                .andExpect(jsonPath("$.content[0].password").doesNotExist())
                .andExpect(jsonPath("$.content[0].email", is("admin@test.com")))
                .andExpect(jsonPath("$.content[0].role", is("ADMIN")));
    }

    @Test
    void pagedUsers_isAdminOnly() throws Exception {
        mockMvc.perform(get("/api/users/paged"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/paged").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyArrayEndpoints_areUnchanged() throws Exception {
        createOrderFor(userToken, 1);

        // The legacy endpoints must still return plain arrays, not envelopes.
        mockMvc.perform(get("/api/orders").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", instanceOf(List.class)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderItems", hasSize(1)));

        mockMvc.perform(get("/api/orders/my").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", instanceOf(List.class)))
                .andExpect(jsonPath("$", hasSize(1)));

        mockMvc.perform(get("/api/users").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", instanceOf(List.class)));
    }
}
