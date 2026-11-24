package com.examly.springapp.controller;

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
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16 — password policy.
 *
 * <p>The rule is: at least 8 printable ASCII characters with lowercase,
 * uppercase, a digit and any non-alphanumeric character. Legitimate special
 * characters such as # and ^ must be accepted, and the rule must match the
 * register form in the frontend.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PasswordPolicyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearUsers() {
        userRepository.deleteAll();
    }

    @Test
    void acceptsHashAndCaretSpecialCharacters() throws Exception {
        assertAccepted("hash@example.test", "Str0ng#Pass");
        assertAccepted("caret@example.test", "An0ther^Pass");
    }

    @Test
    void acceptsOtherPrintableSpecialCharacters() throws Exception {
        assertAccepted("tilde@example.test", "Aa1~passw");
        assertAccepted("bracket@example.test", "Aa1{pass}");
        assertAccepted("backtick@example.test", "Aa1`pass");
    }

    @Test
    void rejectedPasswordsAreReportedForTheRightReason() throws Exception {
        assertRejected("noupper@example.test", "str0ng#pass");
        assertRejected("nolower@example.test", "STR0NG#PASS");
        assertRejected("nodigit@example.test", "Strong#Pass");
        assertRejected("nospecial@example.test", "Str0ngPass1");
        assertRejected("tooshort@example.test", "Aa1#aaa");
        assertRejected("whitespace@example.test", "Aa1# aaaa");
    }

    @Test
    void acceptedPasswordIsPersistedOnlyAsABcryptHash() throws Exception {
        assertAccepted("hashed@example.test", "Str0ng#Pass");

        User stored = userRepository.findByEmail("hashed@example.test").orElseThrow();
        assertThat(stored.getPassword()).isNotEqualTo("Str0ng#Pass");
        assertThat(passwordEncoder.matches("Str0ng#Pass", stored.getPassword())).isTrue();
    }

    private void assertAccepted(String email, String password) throws Exception {
        MvcResult result = register(email, password);
        assertThat(result.getResponse().getStatus())
                .as("password %s for %s should be accepted", password, email)
                .isEqualTo(201);
    }

    private void assertRejected(String email, String password) throws Exception {
        MvcResult result = register(email, password);
        assertThat(result.getResponse().getStatus())
                .as("password %s for %s should be rejected", password, email)
                .isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("Password must be at least 8 characters");
        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    private MvcResult register(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Policy Test",
                                "email", email,
                                "password", password))))
                .andReturn();
    }
}
