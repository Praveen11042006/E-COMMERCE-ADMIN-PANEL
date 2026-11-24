package com.examly.springapp.controller;

import com.examly.springapp.model.Product;
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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13B — product request validation.
 * Valid payloads still return the same Product JSON; invalid payloads now return
 * the central {message, errors{field}} body instead of reaching the database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class ProductValidationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ProductRepository productRepository;

    private String adminToken;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        adminToken = "Bearer " + jwtUtil.generateToken("admin@validation.test", "ADMIN", 1L);
    }

    private Map<String, Object> validPayload() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Valid Product");
        body.put("description", "A valid description");
        body.put("price", 19.99);
        body.put("category", "Electronics");
        body.put("stockQuantity", 5);
        body.put("imageUrl", "https://example.com/p.png");
        return body;
    }

    private void expectFieldError(Map<String, Object> body, String field, String messageFragment)
            throws Exception {
        mockMvc.perform(post("/api/products")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors." + field).value(containsString(messageFragment)));
    }

    @Test
    void validProductIsCreated() throws Exception {
        mockMvc.perform(post("/api/products")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Valid Product"))
                .andExpect(jsonPath("$.stockQuantity").value(5));
    }

    @Test
    void blankNameIsRejectedWithFieldError() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("name", "   ");
        expectFieldError(body, "name", "required");
    }

    @Test
    void zeroPriceIsRejectedWithFieldError() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("price", 0);
        expectFieldError(body, "price", "greater than 0");
    }

    @Test
    void negativePriceIsRejectedWithFieldError() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("price", -12.5);
        expectFieldError(body, "price", "greater than 0");
    }

    @Test
    void negativeStockIsRejectedWithFieldError() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("stockQuantity", -1);
        expectFieldError(body, "stockQuantity", "must not be negative");
    }

    @Test
    void missingCategoryIsRejectedWithFieldError() throws Exception {
        Map<String, Object> body = validPayload();
        body.remove("category");
        expectFieldError(body, "category", "required");
    }

    @Test
    void tooManyDecimalPlacesIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("price", 10.999);
        expectFieldError(body, "price", "2 decimal digits");
    }

    @Test
    void invalidProductIsNeverPersisted() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("price", -1);
        mockMvc.perform(post("/api/products")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());

        org.junit.jupiter.api.Assertions.assertEquals(0, productRepository.count());
    }

    @Test
    void updateRejectsNegativePriceAndLeavesProductUnchanged() throws Exception {
        Product product = productRepository.save(Product.builder()
                .name("Original").description("Original description").price(50.0)
                .category("Misc").stockQuantity(3).build());

        Map<String, Object> body = validPayload();
        body.put("price", -99);
        mockMvc.perform(put("/api/products/" + product.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.price").exists());

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(50.0));
    }

    @Test
    void updateAcceptsValidPayload() throws Exception {
        Product product = productRepository.save(Product.builder()
                .name("Original").description("Original description").price(50.0)
                .category("Misc").stockQuantity(3).build());

        Map<String, Object> body = validPayload();
        body.put("name", "Renamed");
        mockMvc.perform(put("/api/products/" + product.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.price").value(19.99));
    }
}
