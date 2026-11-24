package com.examly.springapp.controller;

import com.examly.springapp.utils.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 10A — JWT verification hardening (negative cases).
 *
 * Verifies that the API rejects every class of invalid token with a controlled
 * 401 (no stack traces, no token echo) and that valid tokens (including role
 * binding) behave exactly as before.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class JwtSecurityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private void createUser(String email, String role) {
        jdbc.update("INSERT INTO users (name, email, password, role, active, created_at) VALUES (?,?,?,?,?,?)",
                "JWT User", email, passwordEncoder.encode("Str0ng!Pass"), role, true,
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
    }

    /** A token signed with a DIFFERENT (but valid-length) key — must fail signature check. */
    private String tokenSignedWithWrongKey() {
        byte[] otherKey = "anotherSecretKeyThatIsDefinitelyLongEnough1234".getBytes();
        return Jwts.builder()
                .setSubject("jwt-user@examly.com")
                .setIssuer(JwtUtil.ISSUER)
                .claim("role", "USER")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(Keys.hmacShaKeyFor(otherKey), SignatureAlgorithm.HS256)
                .compact();
    }

    /** A correctly signed token WITHOUT the required issuer claim. */
    private String tokenWithoutIssuer() {
        return Jwts.builder()
                .setSubject("jwt-user@examly.com")
                .claim("role", "USER")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes()), SignatureAlgorithm.HS256)
                .compact();
    }

    /** A correctly signed, correctly-issued token that is already expired. */
    private String expiredToken() {
        return Jwts.builder()
                .setSubject("jwt-user@examly.com")
                .setIssuer(JwtUtil.ISSUER)
                .claim("role", "USER")
                .setIssuedAt(new Date(System.currentTimeMillis() - 7200000))
                .setExpiration(new Date(System.currentTimeMillis() - 3600000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes()), SignatureAlgorithm.HS256)
                .compact();
    }

    @Test
    void validTokenReachesProtectedEndpoint() throws Exception {
        createUser("jwt-user@examly.com", "USER");
        String token = jwtUtil.generateToken("jwt-user@examly.com", "USER", 1L);

        mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("jwt-user@examly.com"));
    }

    @Test
    void missingTokenIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/orders/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void malformedTokenIsRejectedWith401Not500() throws Exception {
        mockMvc.perform(get("/api/orders/my").header("Authorization", "Bearer not-a-jwt-at-all"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void tokenWithInvalidSignatureIsRejected() throws Exception {
        mockMvc.perform(get("/api/orders/my").header("Authorization", "Bearer " + tokenSignedWithWrongKey()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/orders/my").header("Authorization", "Bearer " + expiredToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutIssuerIsRejected() throws Exception {
        // Phase 10A: tokens must be bound to this application via the issuer claim.
        mockMvc.perform(get("/api/orders/my").header("Authorization", "Bearer " + tokenWithoutIssuer()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/orders/my").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userRoleTokenIsForbiddenOnAdminEndpoint() throws Exception {
        createUser("jwt-user@examly.com", "USER");
        String token = jwtUtil.generateToken("jwt-user@examly.com", "USER", 1L);

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void adminRoleTokenIsAllowedOnAdminEndpoint() throws Exception {
        createUser("jwt-admin@examly.com", "ADMIN");
        String token = jwtUtil.generateToken("jwt-admin@examly.com", "ADMIN", 2L);

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void roleEscalationViaForgedRoleClaimFailsSignatureCheck() throws Exception {
        createUser("jwt-user@examly.com", "USER");
        // Attacker crafts a token with role=ADMIN but signs it with their own
        // key — the signature check must reject it before any role is trusted.
        String forged = Jwts.builder()
                .setSubject("jwt-user@examly.com")
                .setIssuer(JwtUtil.ISSUER)
                .claim("role", "ADMIN")
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 3600000))
                .signWith(Keys.hmacShaKeyFor("attacker-controlled-key-32-bytes-min!!".getBytes()), SignatureAlgorithm.HS256)
                .compact();

        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }
}
