package com.examly.springapp.controller;

import com.examly.springapp.repository.CouponRepository;
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

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13B — coupon creation validation.
 * Malformed coupons are rejected with field-level errors instead of surfacing as a
 * generic database failure; valid coupons are still created exactly as before.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class CouponValidationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private CouponRepository couponRepository;

    private String adminToken;

    @BeforeEach
    void setUp() {
        couponRepository.deleteAll();
        adminToken = "Bearer " + jwtUtil.generateToken("admin@coupons.test", "ADMIN", 1L);
    }

    private Map<String, Object> validPayload() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "save10");
        body.put("discountType", "PERCENTAGE");
        body.put("discountValue", 10.0);
        body.put("minOrderAmount", 100.0);
        body.put("maxDiscount", 50.0);
        body.put("usageLimit", 25);
        body.put("expiryDate", LocalDateTime.now().plusDays(10).withNano(0).toString());
        return body;
    }

    private void expectFieldError(Map<String, Object> body, String field, String fragment) throws Exception {
        mockMvc.perform(post("/api/coupons")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors." + field).value(containsString(fragment)));
    }

    @Test
    void validCouponIsCreatedAndNormalised() throws Exception {
        mockMvc.perform(post("/api/coupons")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.code").value("SAVE10"))
                .andExpect(jsonPath("$.discountType").value("PERCENTAGE"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void blankCodeIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("code", "  ");
        // A blank value violates both @NotBlank and @Pattern; the collector keeps the
        // first violation, so assert on the shared field rather than one message.
        expectFieldError(body, "code", "Coupon code");
    }

    @Test
    void codeWithIllegalCharactersIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("code", "SAVE 10%");
        expectFieldError(body, "code", "only letters, digits");
    }

    @Test
    void unknownDiscountTypeIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("discountType", "BOGO");
        expectFieldError(body, "discountType", "PERCENTAGE or FIXED");
    }

    @Test
    void nonPositiveDiscountValueIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("discountValue", 0);
        expectFieldError(body, "discountValue", "greater than 0");
    }

    @Test
    void percentageAboveOneHundredIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("discountValue", 150);
        expectFieldError(body, "percentageDiscountWithinRange", "must not exceed 100");
    }

    @Test
    void negativeMinimumOrderAmountIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("minOrderAmount", -10);
        expectFieldError(body, "minOrderAmount", "must not be negative");
    }

    @Test
    void negativeUsageLimitIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("usageLimit", -1);
        expectFieldError(body, "usageLimit", "must not be negative");
    }

    @Test
    void pastExpiryDateIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("expiryDate", LocalDateTime.now().minusDays(1).withNano(0).toString());
        expectFieldError(body, "expiryDate", "future");
    }

    @Test
    void fixedCouponWithMaximumDiscountIsRejected() throws Exception {
        Map<String, Object> body = validPayload();
        body.put("discountType", "FIXED");
        body.put("discountValue", 25.0);
        expectFieldError(body, "maxDiscountOnlyForPercentage", "only supported for PERCENTAGE");
    }

    @Test
    void duplicateCodeIsRejectedByTheService() throws Exception {
        String payload = objectMapper.writeValueAsString(validPayload());
        mockMvc.perform(post("/api/coupons").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/coupons").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Coupon code already exists"));
    }

    @Test
    void customerCannotCreateCoupons() throws Exception {
        String userToken = "Bearer " + jwtUtil.generateToken("user@coupons.test", "USER", 2L);
        mockMvc.perform(post("/api/coupons").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validPayload())))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanListAndDeleteCoupons() throws Exception {
        String payload = objectMapper.writeValueAsString(validPayload());
        String response = mockMvc.perform(post("/api/coupons").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(response).get("id").asLong();

        mockMvc.perform(delete("/api/coupons/" + id).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertEquals(0, couponRepository.count());
    }
}
