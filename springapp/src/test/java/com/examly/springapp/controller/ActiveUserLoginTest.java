package com.examly.springapp.controller;

import com.examly.springapp.model.Role;
import com.examly.springapp.model.User;
import com.examly.springapp.repository.UserRepository;
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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9 regression: login must reject inactive accounts (active=false) WITHOUT
 * issuing a JWT, while active accounts continue to log in normally.
 * Password verification still runs first so account status is never leaked to
 * callers who do not hold valid credentials.
 * Runs on the in-memory H2 test database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class ActiveUserLoginTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        userRepository.deleteAll();
        saveUser("active@auth.com", "Active@123", true);
        saveUser("inactive@auth.com", "Inactive@123", false);
    }

    private void saveUser(String email, String rawPw, boolean active) {
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(rawPw));
        u.setName(email.split("@")[0]);
        u.setRole(Role.USER);
        u.setActive(active);
        u.setCreatedAt(LocalDateTime.now());
        userRepository.save(u);
    }

    @Test
    void activeUserLoginSucceedsAndReceivesToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "active@auth.com", "password", "Active@123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.userId").exists())
                .andExpect(jsonPath("$.email").value("active@auth.com"));
    }

    @Test
    void inactiveUserLoginRejectedWithoutToken() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "inactive@auth.com", "password", "Inactive@123"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message")
                        .value("Account is deactivated. Please contact the administrator."))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void inactiveUserWithWrongPasswordStillGets401() throws Exception {
        // Account status must not be disclosed before password verification succeeds.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "inactive@auth.com", "password", "Wrong@123"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void inactiveUserLoginDoesNotUpdateLastLogin() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "inactive@auth.com", "password", "Inactive@123"))))
                .andExpect(status().isForbidden());

        User inactive = userRepository.findByEmail("inactive@auth.com").orElseThrow();
        assertNull(inactive.getLastLogin(), "rejected login must not update lastLogin");
    }
}
