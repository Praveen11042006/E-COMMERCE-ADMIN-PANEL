package com.examly.springapp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 10A — login brute-force protection.
 *
 * Thresholds are lowered for tests (3 failures / short cooldown) so tests run
 * fast and deterministically. Each test method gets a fresh Spring context
 * (@DirtiesContext), which also resets the in-memory rate limiter state.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
@TestPropertySource(properties = {
        "security.login.max-failed-attempts=3",
        "security.login.window-seconds=300",
        "security.login.lockout-seconds=2"
})
public class LoginRateLimitTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    /** Creates a fresh active user directly in the DB for the given email. */
    private void createUser(String email, String rawPassword) {
        jdbc.update("INSERT INTO users (name, email, password, role, active, created_at) VALUES (?,?,?,?,?,?)",
                "Test User", email, passwordEncoder.encode(rawPassword), "USER", true,
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
    }

    private MvcResult login(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn();
    }

    @Test
    void belowThresholdFailuresStillReturn401() throws Exception {
        // 2 failures with threshold 3 — no lockout yet.
        login("below-threshold@examly.com", "wrong-1");
        login("below-threshold@examly.com", "wrong-2");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "below-threshold@examly.com", "password", "wrong-3"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void thresholdReachedNextAttemptGets429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("lockout@examly.com", "wrong-" + i);
        }

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "lockout@examly.com", "password", "wrong-again"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value("Too many failed login attempts. Please try again later."))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void lockedOutAccountCannotLoginWithCorrectPassword() throws Exception {
        createUser("correct-after-lock@examly.com", "Str0ng!Pass");

        for (int i = 0; i < 3; i++) {
            login("correct-after-lock@examly.com", "wrong-" + i);
        }

        // Correct credentials, but still locked out — no token issued.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "correct-after-lock@examly.com", "password", "Str0ng!Pass"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.token").doesNotExist());
    }


    @Test
    void blockedResponseIsIdenticalForUnknownAccounts() throws Exception {
        // Nonexistent account: failures count the same and the 429 body must
        // not reveal whether the account exists (no user enumeration).
        for (int i = 0; i < 3; i++) {
            login("ghost-user-xyz@examly.com", "wrong-" + i);
        }

        MvcResult blocked = login("ghost-user-xyz@examly.com", "still-wrong");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getContentAsString())
                .doesNotContain("not found")
                .doesNotContain("exist");
    }

    @Test
    void cooldownExpiresAndLoginSucceedsAgain() throws Exception {
        createUser("cooldown@examly.com", "Str0ng!Pass");
        for (int i = 0; i < 3; i++) {
            login("cooldown@examly.com", "wrong-" + i);
        }
        assertThat(login("cooldown@examly.com", "Str0ng!Pass").getResponse().getStatus()).isEqualTo(429);

        // lockout-seconds=2 in this test profile; wait for it to expire.
        Thread.sleep(2500);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "cooldown@examly.com", "password", "Str0ng!Pass"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void successfulLoginResetsFailureCounter() throws Exception {
        createUser("reset-after-success@examly.com", "Str0ng!Pass");

        // 2 failures, then success, then 2 more failures — still below threshold.
        login("reset-after-success@examly.com", "wrong-1");
        login("reset-after-success@examly.com", "wrong-2");
        login("reset-after-success@examly.com", "Str0ng!Pass");
        login("reset-after-success@examly.com", "wrong-3");
        login("reset-after-success@examly.com", "wrong-4");

        // Because a success intervened, the email counter restarted; the next
        // attempt is the 3rd failure of the new window — still 401, not 429.
        // (The per-IP threshold is 20 in the default profile, so the shared
        // test IP is not locked out by these 5 failures.)
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", "reset-after-success@examly.com", "password", "wrong-5"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitedResponseBodyHasNoStackTracesOrInternals() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("clean-body@examly.com", "wrong-" + i);
        }

        MvcResult blocked = login("clean-body@examly.com", "wrong");
        String body = blocked.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("at com.")
                .doesNotContain("stacktrace");
    }
}
