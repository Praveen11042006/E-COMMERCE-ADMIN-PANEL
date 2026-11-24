package com.examly.springapp.controller;

import com.examly.springapp.model.Role;
import com.examly.springapp.model.User;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class UserControllerValidationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private User target;
    private User other;
    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        User admin = save("admin@users.test", "Admin@123", Role.ADMIN);
        target = save("target@users.test", "Target@123", Role.USER);
        other = save("other@users.test", "Other@123", Role.USER);
        adminToken = bearer(admin);
        userToken = bearer(target);
    }

    @Test
    void adminCanUpdateAllowedFieldsAndDeactivateUser() throws Exception {
        mockMvc.perform(update(target.getId(), Map.of("name", "Updated User", "email", "updated@users.test", "active", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated User"))
                .andExpect(jsonPath("$.email").value("updated@users.test"))
                .andExpect(jsonPath("$.active").value(false));
        User saved = userRepository.findById(target.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(saved.isActive());
        org.junit.jupiter.api.Assertions.assertEquals(Role.USER, saved.getRole());
    }

    @Test
    void rejectsInvalidEmailAndBlankRequiredFields() throws Exception {
        mockMvc.perform(update(target.getId(), Map.of("name", "", "email", "not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void duplicateEmailReturnsConflictWithoutExposingDatabaseDetails() throws Exception {
        mockMvc.perform(update(target.getId(), Map.of("name", "Target", "email", other.getEmail())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Email already exists"));
    }

    @Test
    void nonAdminCannotUpdateAUser() throws Exception {
        mockMvc.perform(put("/api/users/{id}", other.getId())
                        .header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Nope", "email", "nope@users.test"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsRoleEscalationAndLeavesRoleUntouched() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Target");
        body.put("email", target.getEmail());
        body.put("role", "ADMIN");
        mockMvc.perform(update(target.getId(), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.noUnsupportedFields").exists());
        org.junit.jupiter.api.Assertions.assertEquals(Role.USER,
                userRepository.findById(target.getId()).orElseThrow().getRole());
    }

    @Test
    void hashesNewPasswordAndBlankPasswordKeepsTheExistingHash() throws Exception {
        String originalHash = userRepository.findById(target.getId()).orElseThrow().getPassword();
        mockMvc.perform(update(target.getId(), Map.of("name", "Target", "email", target.getEmail(), "password", "Changed@123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").doesNotExist());
        String changedHash = userRepository.findById(target.getId()).orElseThrow().getPassword();
        org.junit.jupiter.api.Assertions.assertNotEquals(originalHash, changedHash);
        org.junit.jupiter.api.Assertions.assertTrue(passwordEncoder.matches("Changed@123", changedHash));
        mockMvc.perform(update(target.getId(), Map.of("name", "Target", "email", target.getEmail(), "password", "")))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(changedHash,
                userRepository.findById(target.getId()).orElseThrow().getPassword());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder update(Long id, Map<String, Object> body) throws Exception {
        return put("/api/users/{id}", id).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private String bearer(User user) { return "Bearer " + jwtUtil.generateToken(user.getEmail(), user.getRole().name(), user.getId()); }

    private User save(String email, String password, Role role) {
        User user = new User();
        user.setName(email.substring(0, email.indexOf('@')));
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(role);
        user.setActive(true);
        user.setCreatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }
}
