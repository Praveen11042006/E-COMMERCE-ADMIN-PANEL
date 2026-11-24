package com.examly.springapp.service;

import com.examly.springapp.dto.OrderStatsDto;
import com.examly.springapp.dto.PageRequestParams;
import com.examly.springapp.model.Order;
import com.examly.springapp.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.io.StringWriter;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class OrderAnalyticsOptimizationTest {
    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;

    @BeforeEach
    void clearOrders() { orderRepository.deleteAll(); }

    @Test
    void aggregateStatsMatchTheExistingStatusAndRevenueSemantics() {
        save("PENDING", 10, LocalDateTime.of(2026, 1, 1, 0, 0));
        save("PROCESSING", 20, LocalDateTime.of(2026, 1, 2, 0, 0));
        save("SHIPPED", 30, LocalDateTime.of(2026, 1, 3, 0, 0));
        save("DELIVERED", 40, LocalDateTime.of(2026, 1, 4, 0, 0));
        save("CANCELLED", 50, LocalDateTime.of(2026, 1, 5, 0, 0));
        save("UNKNOWN", 60, LocalDateTime.of(2026, 1, 6, 0, 0));

        OrderStatsDto stats = orderService.getOrderStats();
        assertEquals(6, stats.getTotal());
        assertEquals(1, stats.getPending());
        assertEquals(1, stats.getProcessing());
        assertEquals(1, stats.getShipped());
        assertEquals(1, stats.getDelivered());
        assertEquals(1, stats.getCancelled());
        assertEquals(160.0, stats.getTotalRevenue());
        assertEquals(32.0, stats.getAverageOrderValue());
    }

    @Test
    void aggregateStatsAreZeroForAnEmptyDataSet() {
        OrderStatsDto stats = orderService.getOrderStats();
        assertEquals(0, stats.getTotal());
        assertEquals(0, stats.getTotalRevenue());
        assertEquals(0, stats.getAverageOrderValue());
        assertEquals(0, stats.getCancelled());
    }

    @Test
    void streamingCsvKeepsHeadersEscapingAndInclusiveDateBoundaries() throws Exception {
        save("PENDING", 12.5, LocalDateTime.of(2026, 3, 1, 0, 0), "A, \"quoted\"\ncustomer");
        save("PENDING", 20, LocalDateTime.of(2026, 3, 2, 0, 0), "outside");

        PageRequestParams params = PageRequestParams.parseOrderParams(null, null, "id,asc")
                .withDateFrom("2026-03-01T00:00:00")
                .withDateTo("2026-03-01T00:00:00");
        StringWriter output = new StringWriter();
        orderService.writeOrdersCsv(null, params, output);
        String csv = output.toString();

        assertTrue(csv.startsWith("Order ID,Customer Name,Email,Phone,Status,Total Amount,Order Date,Shipping Address,Items Count\n"));
        assertTrue(csv.contains("\"A, \"\"quoted\"\"\ncustomer\""));
        assertTrue(csv.contains("12.5"));
        assertFalse(csv.contains("outside"));
    }

    @Test
    void streamingCsvTraversesMoreThanOneBoundedPage() throws Exception {
        for (int i = 0; i < 501; i++) {
            save("PENDING", i, LocalDateTime.of(2026, 4, 1, 0, 0), "row-" + i);
        }
        StringWriter output = new StringWriter();
        orderService.writeOrdersCsv(null, PageRequestParams.parseOrderParams(null, null, "id,asc"), output);
        // 500 is the service batch size; 501 proves the export advances to a
        // subsequent bounded page without changing the output contract.
        assertEquals(502, output.toString().split("\\n").length);
    }

    private void save(String status, double total, LocalDateTime date) {
        save(status, total, date, "Customer");
    }

    private void save(String status, double total, LocalDateTime date, String name) {
        orderRepository.save(Order.builder().customerName(name).customerEmail("test@example.com")
                .shippingAddress("1 Main St").status(status).totalAmount(total).orderDate(date).build());
    }
}
