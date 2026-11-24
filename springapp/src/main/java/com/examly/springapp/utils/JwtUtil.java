package com.examly.springapp.utils;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;

@Component
public class JwtUtil {

    /**
     * Phase 10A: tokens are bound to this application via an issuer claim so a
     * token minted by another service sharing the same HMAC secret cannot be
     * replayed against this API. Note: adding the issuer invalidates any token
     * issued before this change; users simply log in again (expiry is 1 hour).
     */
    public static final String ISSUER = "examly-ecom";

    @Value("${jwt.secret}")
    private String secretKey;

    @Value("${jwt.expirationMs:3600000}")
    private long expirationMs;

    private Key getSigningKey() {
        byte[] keyBytes = JwtSecretValidator.requireMinimumLength(secretKey);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /** Validate the configured key at startup instead of waiting for token use. */
    @PostConstruct
    public void validateSigningKey() {
        getSigningKey();
    }

    public String generateToken(String email, String role, Long userId) {
        return Jwts.builder()
                .setSubject(email)
                .setIssuer(ISSUER)
                .claim("role", role)
                .claim("userId", userId)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractEmail(String token) {
        return getClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return (String) getClaims(token).get("role");
    }

    public Long extractUserId(String token) {
        Object userId = getClaims(token).get("userId");
        if (userId instanceof Integer) return ((Integer) userId).longValue();
        if (userId instanceof Long) return (Long) userId;
        return null;
    }

    public boolean validateToken(String token) {
        try {
            getClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims getClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .requireIssuer(ISSUER)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private boolean isTokenExpired(String token) {
        return getClaims(token).getExpiration().before(new Date());
    }
}
