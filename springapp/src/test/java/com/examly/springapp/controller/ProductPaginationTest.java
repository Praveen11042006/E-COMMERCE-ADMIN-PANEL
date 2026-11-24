package com.examly.springapp.controller;

import com.examly.springapp.model.Product;
import com.examly.springapp.repository.ProductRepository;
import com.examly.springapp.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13B — server-side product pagination.
 * The paged endpoint preserves the legacy filters, adds search, and enforces the
 * shared page-size/sort limits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class ProductPaginationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProductRepository productRepository;
    @Autowired private JwtUtil jwtUtil;

    /** Phase 16: /api/products/stats is admin-only. */
    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = "Bearer " + jwtUtil.generateToken("admin@test.com", "ADMIN", 1L);
        productRepository.deleteAll();
        List<Product> products = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            products.add(Product.builder()
                    .name("Product " + String.format("%02d", i))
                    .description("Description " + i)
                    .price(10.0 * i)
                    .category(i % 2 == 0 ? "Electronics" : "Apparel")
                    .stockQuantity(i)
                    .build());
        }
        productRepository.saveAll(products);
    }

    @Test
    void firstPageReturnsEnvelopeAndRespectsSize() throws Exception {
        mockMvc.perform(get("/api/products/paged?page=0&size=5&sort=name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.content[0].name").value("Product 01"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.totalElements").value(15))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
    }

    @Test
    void lastPageIsMarkedAsLast() throws Exception {
        mockMvc.perform(get("/api/products/paged?page=2&size=5&sort=name,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(5)))
                .andExpect(jsonPath("$.content[0].name").value("Product 11"))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.first").value(false));
    }

    @Test
    void sortingByPriceDescendingWorks() throws Exception {
        mockMvc.perform(get("/api/products/paged?page=0&size=3&sort=price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].price").value(150.0))
                .andExpect(jsonPath("$.content[1].price").value(140.0));
    }

    @Test
    void categoryFilterIsPreserved() throws Exception {
        mockMvc.perform(get("/api/products/paged?category=Electronics&size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7));
    }

    @Test
    void priceRangeFilterIsPreserved() throws Exception {
        mockMvc.perform(get("/api/products/paged?minPrice=100&maxPrice=120&size=100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void searchMatchesNameAndDescription() throws Exception {
        // .param() lets MockMvc encode the space properly instead of leaving it in the URI.
        mockMvc.perform(get("/api/products/paged").param("size", "100").param("search", "Product 07"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Product 07"));

        mockMvc.perform(get("/api/products/paged").param("size", "100").param("search", "Description 3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void defaultPageSizeIsTen() throws Exception {
        mockMvc.perform(get("/api/products/paged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(10)))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void excessivePageSizeIsRejected() throws Exception {
        mockMvc.perform(get("/api/products/paged?size=101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("size must be between 1 and 100")));
    }

    @Test
    void negativePageIsRejected() throws Exception {
        mockMvc.perform(get("/api/products/paged?page=-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("page must be zero or greater")));
    }

    @Test
    void unsupportedSortFieldIsRejected() throws Exception {
        mockMvc.perform(get("/api/products/paged?sort=password,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Unsupported sort field")));
    }

    @Test
    void invertedPriceRangeIsRejected() throws Exception {
        mockMvc.perform(get("/api/products/paged?minPrice=100&maxPrice=10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("minPrice must not be greater than maxPrice")));
    }

    @Test
    void negativePriceFilterIsRejected() throws Exception {
        mockMvc.perform(get("/api/products/paged?minPrice=-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("minPrice must not be negative")));
    }

    @Test
    void statsSummariseTheWholeTableNotJustOnePage() throws Exception {
        // Fixture: 15 products, price = 10*i, stock = i  ->  total value = 10 * sum(i^2) = 12400.
        mockMvc.perform(get("/api/products/stats").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(15))
                .andExpect(jsonPath("$.inStock").value(15))
                .andExpect(jsonPath("$.outOfStock").value(0))
                .andExpect(jsonPath("$.lowStock").value(10))
                .andExpect(jsonPath("$.totalValue").value(12400.0));
    }

    @Test
    void statsCountOutOfStockProductsSeparately() throws Exception {
        productRepository.deleteAll();
        productRepository.save(Product.builder().name("Empty").description("d").price(50.0)
                .category("Misc").stockQuantity(0).build());
        productRepository.save(Product.builder().name("Low").description("d").price(10.0)
                .category("Misc").stockQuantity(3).build());

        mockMvc.perform(get("/api/products/stats").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(2))
                .andExpect(jsonPath("$.inStock").value(1))
                .andExpect(jsonPath("$.outOfStock").value(1))
                .andExpect(jsonPath("$.lowStock").value(1))
                .andExpect(jsonPath("$.totalValue").value(30.0));
    }

    @Test
    void legacyArrayEndpointStillReturnsEveryProduct() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(15)));
    }
}
