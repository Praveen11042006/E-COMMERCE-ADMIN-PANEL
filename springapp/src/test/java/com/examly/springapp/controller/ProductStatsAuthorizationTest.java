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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16 — inventory statistics are admin-only; the product catalogue stays public.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ProductStatsAuthorizationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProductRepository productRepository;
    @Autowired private JwtUtil jwtUtil;

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        productRepository.save(Product.builder().name("InStock").description("d").price(10.0)
                .category("Misc").stockQuantity(5).build());
        productRepository.save(Product.builder().name("SoldOut").description("d").price(20.0)
                .category("Misc").stockQuantity(0).build());

        userToken = "Bearer " + jwtUtil.generateToken("user@test.com", "USER", 2L);
        adminToken = "Bearer " + jwtUtil.generateToken("admin@test.com", "ADMIN", 1L);
    }

    @Test
    void anonymousCallerCannotReadStatistics() throws Exception {
        mockMvc.perform(get("/api/products/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerCannotReadStatistics() throws Exception {
        mockMvc.perform(get("/api/products/stats").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorReadsStatistics() throws Exception {
        mockMvc.perform(get("/api/products/stats").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalProducts").value(2))
                .andExpect(jsonPath("$.inStock").value(1))
                .andExpect(jsonPath("$.outOfStock").value(1))
                .andExpect(jsonPath("$.totalValue").value(50.0));
    }

    @Test
    void publicCatalogueEndpointsStayPublic() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
        mockMvc.perform(get("/api/products/paged"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }
}
