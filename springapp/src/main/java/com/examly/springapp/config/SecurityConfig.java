package com.examly.springapp.config;

import com.examly.springapp.utils.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> {})
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Authentication required\"}");
                })
                .accessDeniedHandler((request, response, deniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Access denied: insufficient permissions\"}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                // Public auth endpoints
                .requestMatchers("/auth/login", "/auth/register").permitAll()
                // Actuator health probe: exposed only as /actuator/health and it must
                // stay reachable unauthenticated for container/orchestrator checks.
                .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                // Inventory statistics are an admin dashboard concern (whole-table
                // counts/valuation), not public catalogue data. Must be declared
                // before the public product GET rule below.
                .requestMatchers(HttpMethod.GET, "/api/products/stats").hasRole("ADMIN")
                // Products: read is public, write requires ADMIN
                .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                // Admin-only endpoints
                .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/products/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/products/**").hasRole("ADMIN")
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // Analytics / ML dashboards are admin-only
                .requestMatchers("/api/analytics/**", "/api/rfm/**", "/api/forecast/**",
                                 "/api/demand/**", "/api/risk/**", "/api/audit/**").hasRole("ADMIN")
                // Coupons: validation is available to customers, management is admin-only (method-level)
                .requestMatchers(HttpMethod.GET, "/api/coupons").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/coupons").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/coupons/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/coupons/**").hasRole("ADMIN")
                // Orders: authenticated users only (ownership enforced in service)
                .requestMatchers("/api/orders/**").authenticated()
                // Payments / invoices: authenticated users (ownership enforced in service)
                .requestMatchers("/api/payments/**").authenticated()
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            // Phase 10A: explicit hardening headers.
            // - nosniff: browsers must not MIME-sniff JSON responses.
            // - frameOptions DENY: API responses must never be framed (clickjacking).
            // - Referrer-Policy: avoid leaking URLs (order ids etc.) to third parties.
            // - Permissions-Policy: deny powerful browser features to any page rendering API data.
            // - HSTS: written by Spring ONLY on HTTPS requests, so plain-HTTP localhost dev is unaffected.
            // - CSP: this API serves JSON only (the React SPA is served separately), so a strict
            //   'default-src none' policy cannot break the frontend while locking down any
            //   accidental HTML rendering of API responses.
            .headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                // NOTE: permissionsPolicy() must be last — it returns the nested
                // PermissionsPolicyConfig, not HeadersConfigurer, ending the chain.
                .permissionsPolicy(permissions -> permissions.policy("geolocation=(), camera=(), microphone=(), payment=()"))
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
