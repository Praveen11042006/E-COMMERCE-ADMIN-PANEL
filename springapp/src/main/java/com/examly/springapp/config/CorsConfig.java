package com.examly.springapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Phase 10A: CORS is enforced at the Spring Security filter level (the
 * CorsConfigurationSource bean is picked up by http.cors() in SecurityConfig),
 * so preflights are answered before authentication and the rules apply
 * uniformly to every endpoint.
 *
 * Hardening over the previous WebMvcConfigurer version:
 *  - Exact origins only (allowedOrigins, not allowedOriginPatterns) — patterns
 *    would silently accept "*" which, with credentials enabled, is forbidden.
 *  - "*" and "null" entries from the environment are dropped defensively.
 *  - Explicit allowed headers (Authorization, Content-Type, Accept) instead of "*".
 *  - maxAge reduces preflight volume for the React dev server.
 */
@Configuration
public class CorsConfig {

    /**
     * Comma-separated list of allowed origins. Override with the APP_CORS_ORIGINS
     * environment variable in production. Defaults cover local dev servers.
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8081}")
    private String allowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                // Never allow credentials against a wildcard or the literal
                // "null" origin — both are insecure and browsers reject them
                // with credentials anyway.
                .filter(s -> !s.equals("*") && !s.equalsIgnoreCase("null"))
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}

