package com.examly.springapp.controller;

import com.examly.springapp.model.Coupon;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 13D — admin coupon edit endpoint.
 *
 * <p>Guards that an edit updates only the editable discount fields, keeps the
 * redemption counter and activation flag, rejects duplicate codes and invalid
 * values, and stays ADMIN-only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class CouponUpdateTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private CouponRepository couponRepository;

    private String adminToken;
    private String userToken;
    private Long couponId;

    @BeforeEach
    void setUp() {
        couponRepository.deleteAll();
        adminToken = "Bearer " + jwtUtil.generateToken("admin@edit.test", "ADMIN", 1L);
        userToken = "Bearer " + jwtUtil.generateToken("user@edit.test", "USER", 2L);

        Coupon coupon = couponRepository.save(Coupon.builder()
                .code("SAVE10")
                .discountType("PERCENTAGE")
                .discountValue(10)
                .minOrderAmount(100)
                .maxDiscount(50)
                .usageLimit(25)
                .usedCount(3)          // already redeemed
                .active(false)         // retired by an admin
                .expiryDate(LocalDateTime.now().plusDays(10).withNano(0))
                .build());
        couponId = coupon.getId();
    }

    private Map<String, Object> payload(String code, double value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("discountType", "PERCENTAGE");
        body.put("discountValue", value);
        body.put("minOrderAmount", 200.0);
        body.put("maxDiscount", 75.0);
        body.put("usageLimit", 40);
        body.put("expiryDate", LocalDateTime.now().plusDays(30).withNano(0).toString());
        return body;
    }

    @Test
    void adminUpdatesDiscountFieldsAndUsageIsPreserved() throws Exception {
        mockMvc.perform(put("/api/coupons/" + couponId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("save15", 15))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(couponId))
                .andExpect(jsonPath("$.code").value("SAVE15"))
                .andExpect(jsonPath("$.discountValue").value(15.0))
                .andExpect(jsonPath("$.minOrderAmount").value(200.0))
                .andExpect(jsonPath("$.maxDiscount").value(75.0))
                .andExpect(jsonPath("$.usageLimit").value(40))
                // stored history / activation are not editable
                .andExpect(jsonPath("$.usedCount").value(3))
                .andExpect(jsonPath("$.active").value(false));

        Coupon stored = couponRepository.findById(couponId).orElseThrow();
        assertThat(stored.getUsedCount()).isEqualTo(3);
        assertThat(stored.isActive()).isFalse();
    }

    @Test
    void duplicateCodeIsRejected() throws Exception {
        couponRepository.save(Coupon.builder()
                .code("OTHER5").discountType("FIXED").discountValue(5)
                .minOrderAmount(0).maxDiscount(0).usageLimit(0).usedCount(0)
                .active(true).build());

        mockMvc.perform(put("/api/coupons/" + couponId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("other5", 10))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already exists")));
    }

    @Test
    void invalidDiscountAndExpiryAreRejectedByValidation() throws Exception {
        mockMvc.perform(put("/api/coupons/" + couponId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("SAVE10", 150))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.percentageDiscountWithinRange")
                        .value(containsString("must not exceed 100")));

        Map<String, Object> past = payload("SAVE10", 10);
        past.put("expiryDate", LocalDateTime.now().minusDays(1).withNano(0).toString());
        mockMvc.perform(put("/api/coupons/" + couponId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(past)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiryDate").value(containsString("future")));
    }

    @Test
    void onlyAdminMayEditCoupons() throws Exception {
        mockMvc.perform(put("/api/coupons/" + couponId)
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("SAVE10", 10))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/coupons/" + couponId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload("SAVE10", 10))))
                .andExpect(status().isUnauthorized());
    }
}
