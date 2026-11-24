package com.examly.springapp.controller;

import com.examly.springapp.model.Order;
import com.examly.springapp.repository.OrderRepository;
import com.examly.springapp.repository.ProductRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16 — regression coverage for the admin CSV export contract.
 *
 * <p>Covers authorization, download headers, the exact header row, CSV escaping,
 * filter parity with {@code GET /api/orders/paged}, one row per order, the empty
 * export and the streaming path across the 500-row batch boundary. Runs on
 * in-memory H2 only; no MySQL connection is made.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderCsvExportTest {

    private static final String HEADER =
            "Order ID,Customer Name,Email,Phone,Status,Total Amount,Order Date,Shipping Address,Items Count";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        adminToken = "Bearer " + jwtUtil.generateToken("admin@test.com", "ADMIN", 1L);
        userToken = "Bearer " + jwtUtil.generateToken("user@test.com", "USER", 2L);
    }

    // ---------- authorization ----------

    @Test
    void anonymousCallerIsRejected() throws Exception {
        mockMvc.perform(get("/api/orders/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerIsForbidden() throws Exception {
        mockMvc.perform(get("/api/orders/export").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    // ---------- headers and layout ----------

    @Test
    void adminReceivesCsvDownloadHeaders() throws Exception {
        save("PENDING", 10.0, LocalDateTime.of(2026, 1, 1, 10, 0), "Cust");

        MvcResult result = performExport(adminToken);
        String contentType = result.getResponse().getContentType();
        String disposition = result.getResponse().getHeader("Content-Disposition");

        assertThat(contentType).contains("text/csv");
        assertThat(disposition).startsWith("attachment;");
        assertThat(disposition).contains("orders-export-");
        assertThat(disposition).endsWith(".csv\"");
    }

    @Test
    void headerRowKeepsItsExactColumnNamesAndOrder() throws Exception {
        save("PENDING", 10.0, LocalDateTime.of(2026, 1, 1, 10, 0), "Cust");

        String csv = exportCsv(adminToken);
        assertThat(csv.split("\n")[0]).isEqualTo(HEADER);
    }

    @Test
    void oneCsvRowPerOrderInIdOrder() throws Exception {
        Order first = save("PENDING", 10.0, LocalDateTime.of(2026, 1, 1, 10, 0), "First");
        Order second = save("SHIPPED", 20.0, LocalDateTime.of(2026, 1, 2, 10, 0), "Second");
        Order third = save("DELIVERED", 30.0, LocalDateTime.of(2026, 1, 3, 10, 0), "Third");

        String csv = exportCsv(adminToken, "sort", "id,asc");
        String[] lines = csv.split("\n");

        assertThat(lines).hasSize(4);
        assertThat(lines[1]).startsWith(first.getId() + ",");
        assertThat(lines[2]).startsWith(second.getId() + ",");
        assertThat(lines[3]).startsWith(third.getId() + ",");
        assertThat(lines).hasSize(1 + 3);
    }

    @Test
    void commasQuotesAndNewlinesAreEscaped() throws Exception {
        save("PENDING", 12.5, LocalDateTime.of(2026, 1, 1, 10, 0),
                "Doe, Jane", "jane\"quoted@example.com", "12 Main St\nApt 4");

        String csv = exportCsv(adminToken);

        assertThat(csv).contains("\"Doe, Jane\"");
        assertThat(csv).contains("\"jane\"\"quoted@example.com\"");
        assertThat(csv).contains("\"12 Main St\nApt 4\"");
        // The comma inside the quoted name must not add a column separator: the
        // data row still has the documented 9 columns when parsed naively on
        // unquoted commas is NOT valid, so assert the fixed layout instead.
        assertThat(csv).contains("\"Doe, Jane\",\"jane\"\"quoted@example.com\",,\"PENDING\",12.5,");
    }

    @Test
    void emptyExportContainsOnlyTheHeader() throws Exception {
        String csv = exportCsv(adminToken);
        assertThat(csv).isEqualTo(HEADER);
    }

    // ---------- filter parity with the paginated list ----------

    @Test
    void statusFilterMatchesThePagedEndpoint() throws Exception {
        Order pending = save("PENDING", 100.0, LocalDateTime.of(2026, 1, 1, 9, 0), "Alpha");
        Order shipped = save("SHIPPED", 200.0, LocalDateTime.of(2026, 1, 5, 9, 0), "Beta");
        save("CANCELLED", 300.0, LocalDateTime.of(2026, 1, 10, 9, 0), "Gamma");

        List<Long> pagedIds = pagedIds(adminToken, "status", "SHIPPED");
        List<Long> exportedIds = exportedIds(exportCsv(adminToken, "status", "SHIPPED"));

        assertThat(pagedIds).containsExactly(shipped.getId());
        assertThat(exportedIds).containsExactlyElementsOf(pagedIds);
        assertThat(exportedIds).doesNotContain(pending.getId());
    }

    @Test
    void searchFilterMatchesThePagedEndpoint() throws Exception {
        Order alpha = save("PENDING", 100.0, LocalDateTime.of(2026, 1, 1, 9, 0), "Alpha Customer");
        save("PENDING", 100.0, LocalDateTime.of(2026, 1, 1, 9, 0), "Beta Customer");

        List<Long> pagedIds = pagedIds(adminToken, "search", "Alpha");
        List<Long> exportedIds = exportedIds(exportCsv(adminToken, "search", "Alpha"));

        assertThat(pagedIds).containsExactly(alpha.getId());
        assertThat(exportedIds).containsExactlyElementsOf(pagedIds);
    }

    @Test
    void amountAndDateFiltersMatchThePagedEndpoint() throws Exception {
        save("PENDING", 50.0, LocalDateTime.of(2026, 2, 1, 9, 0), "Early");
        Order inRange = save("PENDING", 150.0, LocalDateTime.of(2026, 2, 10, 9, 0), "Middle");
        save("PENDING", 900.0, LocalDateTime.of(2026, 2, 20, 9, 0), "Late");

        List<Long> pagedIds = pagedIds(adminToken,
                "dateFrom", "2026-02-05", "dateTo", "2026-02-15",
                "minAmount", "100", "maxAmount", "200");
        List<Long> exportedIds = exportedIds(exportCsv(adminToken,
                "dateFrom", "2026-02-05", "dateTo", "2026-02-15",
                "minAmount", "100", "maxAmount", "200"));

        assertThat(pagedIds).containsExactly(inRange.getId());
        assertThat(exportedIds).containsExactlyElementsOf(pagedIds);
    }

    @Test
    void invalidFiltersAreRejectedWithBadRequest() throws Exception {
        mockMvc.perform(get("/api/orders/export").header("Authorization", adminToken)
                        .param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/orders/export").header("Authorization", adminToken)
                        .param("dateFrom", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    // ---------- streaming / batching ----------

    @Test
    void exportStreamsAcrossTheFiveHundredRowBatchBoundary() throws Exception {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            orders.add(Order.builder()
                    .customerName("Batch " + i)
                    .customerEmail("batch" + i + "@example.com")
                    .shippingAddress("1 St")
                    .status("PENDING")
                    .totalAmount(i)
                    .orderDate(LocalDateTime.of(2026, 3, 1, 0, 0))
                    .build());
        }
        orderRepository.saveAll(orders);

        String csv = exportCsv(adminToken, "sort", "id,asc");

        // 1 header + 501 data rows, produced by two bounded server-side pages.
        assertThat(csv.split("\n")).hasSize(502);
        assertThat(csv.split("\n")[0]).isEqualTo(HEADER);
    }

    // ---------- helpers ----------

    private MvcResult performExport(String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/orders/export")
                .header("Authorization", token)
                .accept(MediaType.ALL);
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(request().asyncStarted())
                .andReturn();
        // StreamingResponseBody writes asynchronously; waiting on the result drains
        // the same response without issuing a second (uncredentialed) request.
        result.getAsyncResult();
        return result;
    }

    private String exportCsv(String token, String... params) throws Exception {
        return performExport(token, params).getResponse().getContentAsString();
    }

    private List<Long> exportedIds(String csv) {
        List<Long> ids = new ArrayList<>();
        String[] lines = csv.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) continue;
            ids.add(Long.parseLong(line.substring(0, line.indexOf(','))));
        }
        return ids;
    }

    private List<Long> pagedIds(String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/orders/paged")
                .header("Authorization", token)
                .param("size", "100")
                .param("sort", "id,asc");
        for (int i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(json).path("content");
        List<Long> ids = new ArrayList<>();
        content.forEach(node -> ids.add(node.path("id").asLong()));
        return ids;
    }

    private Order save(String status, double total, LocalDateTime date, String name) {
        return save(status, total, date, name, "customer@example.com", "1 Main St");
    }

    private Order save(String status, double total, LocalDateTime date, String name,
                       String email, String address) {
        return orderRepository.save(Order.builder()
                .customerName(name)
                .customerEmail(email)
                .shippingAddress(address)
                .status(status)
                .totalAmount(total)
                .orderDate(date)
                .build());
    }
}
