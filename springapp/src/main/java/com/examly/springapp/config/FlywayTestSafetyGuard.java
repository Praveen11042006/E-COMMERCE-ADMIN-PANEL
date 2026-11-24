package com.examly.springapp.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

/**
 * Rejects unsafe Flyway test settings before Spring creates Flyway's bean.
 * This guard intentionally applies only when the flyway-test profile is active.
 */
public final class FlywayTestSafetyGuard implements EnvironmentPostProcessor, Ordered {

    static final String FLYWAY_TEST_PROFILE = "flyway-test";
    private static final String REQUIRED_DATABASE = "ecom_flyway_test";
    private static final String REQUIRED_USERNAME = "flyway_test";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment);
    }

    static void validate(ConfigurableEnvironment environment) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains(FLYWAY_TEST_PROFILE)) {
            return;
        }

        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw rejected("flyway-test must not be combined with the prod profile");
        }
        if (!environment.getProperty("spring.flyway.enabled", Boolean.class, false)) {
            throw rejected("spring.flyway.enabled must be true for flyway-test");
        }

        String url = required(environment, "spring.datasource.url");
        String username = required(environment, "spring.datasource.username");
        if (!REQUIRED_USERNAME.equals(username)) {
            throw rejected("spring.datasource.username must identify the dedicated Flyway test account");
        }
        validateJdbcTarget(url);
    }

    private static String required(ConfigurableEnvironment environment, String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw rejected(property + " must be supplied for flyway-test");
        }
        return value;
    }

    private static void validateJdbcTarget(String jdbcUrl) {
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw rejected("spring.datasource.url must use the dedicated MySQL test target");
        }

        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort() != 3306
                    || !("/" + REQUIRED_DATABASE).equals(uri.getPath()) || uri.getUserInfo() != null) {
                throw rejected("spring.datasource.url must target only the dedicated Flyway test database");
            }
        } catch (URISyntaxException e) {
            throw rejected("spring.datasource.url must be a valid dedicated MySQL test URL");
        }
    }

    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException("Flyway test safety check failed: " + reason);
    }

    @Override
    public int getOrder() {
        // Configuration data must be loaded first; this still precedes all bean creation,
        // including FlywayAutoConfiguration.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
