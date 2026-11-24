package com.examly.springapp.controller;

import com.examly.springapp.model.Order;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifies the authorization matrix:
 *  - customers cannot list users, modify products, register admins, or view others' orders
 *  - unauthenticated requests get 401
 *  - password hashes are never serialized
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class SecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;

    private String adminToken;
    private String userToken;
    private String otherUserToken;
    private Long orderOfUserId;

    @BeforeEach
    void setup() {
        orderRepository.deleteAll();
        productRepository.deleteAll();
        userRepository.deleteAll();

        User admin = saveUser("admin@sec.com", "Admin@123", Role.ADMIN);
        User ua = saveUser("alice@sec.com", "Alice@123", Role.USER);
        User ub = saveUser("bob@sec.com", "Bobby@123", Role.USER);

        adminToken = "Bearer " + jwtUtil.generateToken(admin.getEmail(), "ADMIN", admin.getId());
        userToken = "Bearer " + jwtUtil.generateToken(ua.getEmail(), "USER", ua.getId());
        otherUserToken = "Bearer " + jwtUtil.generateToken(ub.getEmail(), "USER", ub.getId());

        Order o = Order.builder()
                .customerName("Alice")
                .customerEmail("alice@sec.com")
                .shippingAddress("1 Alice St")
                .status("PENDING")
                .orderDate(LocalDateTime.now())
                .userId(ua.getId())
                .totalAmount(100.0)
                .build();
        orderOfUserId = orderRepository.save(o).getId();
    }

    private User saveUser(String email, String rawPw, Role role) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(rawPw));
        u.setName(email.split("@")[0]);
        u.setRole(role);
        u.setActive(true);
        u.setCreatedAt(LocalDateTime.now());
        return userRepository.save(u);
    }

    @Test
    void test1_customerCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void test2_customerCannotViewAnotherCustomersOrder() throws Exception {
        mockMvc.perform(get("/api/orders/my/" + orderOfUserId).header("Authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/orders/my/" + orderOfUserId).header("Authorization", otherUserToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void test3_unauthenticatedCannotAccessAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void test4_customerCannotModifyProducts() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Hack", "description", "x", "price", 1.0,
                "category", "x", "stockQuantity", 1));
        mockMvc.perform(post("/api/products").header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void test5_customerCannotRegisterAdmin() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", "Evil", "email", "evil@sec.com", "password", "Evil@1234"));
        mockMvc.perform(post("/auth/register/admin").header("Authorization", userToken)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void test6_adminCanListUsersAndPasswordHashIsNeverSerialized() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].password").doesNotExist())
                .andExpect(jsonPath("$[0].email").exists());
    }

    @Test
    void test7_currentUserEndpointReturnsOwnProfileWithoutPassword() throws Exception {
        mockMvc.perform(get("/auth/me").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@sec.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }
}
