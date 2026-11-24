package com.examly.springapp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionConfigurationValidatorTest {

    private static final String DB_URL = "jdbc:mysql://db.example.com:3306/ecom";
    private static final String USERNAME = "application_user";
    private static final String PASSWORD = "test-password";
    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef";
    private static final String ORIGINS = "https://shop.example.com,https://admin.example.com:8443";

    @Test
    void rejectsMissingProductionDatabaseUrl() {
        assertInvalid(null, USERNAME, PASSWORD, JWT_SECRET, ORIGINS, "DB_URL");
    }

    @Test
    void rejectsMissingProductionDatabaseUsername() {
        assertInvalid(DB_URL, " ", PASSWORD, JWT_SECRET, ORIGINS, "DB_USERNAME");
    }

    @Test
    void rejectsMissingProductionDatabasePassword() {
        assertInvalid(DB_URL, USERNAME, "", JWT_SECRET, ORIGINS, "DB_PASSWORD");
    }

    @Test
    void rejectsMissingProductionJwtSecret() {
        assertInvalid(DB_URL, USERNAME, PASSWORD, null, ORIGINS, "JWT_SECRET");
    }

    @Test
    void rejectsMissingProductionCorsOrigins() {
        assertInvalid(DB_URL, USERNAME, PASSWORD, JWT_SECRET, "", "APP_CORS_ORIGINS");
    }

    @Test
    void rejectsShortJwtSecret() {
        assertInvalid(DB_URL, USERNAME, PASSWORD, "too-short", ORIGINS, "JWT_SECRET");
    }

    @Test
    void rejectsNonMysqlJdbcUrl() {
        assertInvalid("jdbc:postgresql://db.example.com/ecom", USERNAME, PASSWORD, JWT_SECRET, ORIGINS, "DB_URL");
    }

    @Test
    void rejectsUnsafeAndMalformedCorsOrigins() {
        assertInvalid(DB_URL, USERNAME, PASSWORD, JWT_SECRET, "*", "APP_CORS_ORIGINS");
        assertInvalid(DB_URL, USERNAME, PASSWORD, JWT_SECRET, "https://localhost:5173", "APP_CORS_ORIGINS");
        assertInvalid(DB_URL, USERNAME, PASSWORD, JWT_SECRET, "http://shop.example.com", "APP_CORS_ORIGINS");
        assertInvalid(DB_URL, USERNAME, PASSWORD, JWT_SECRET, "https://shop.example.com,", "APP_CORS_ORIGINS");
        assertInvalid(DB_URL, USERNAME, PASSWORD, JWT_SECRET, "https://", "APP_CORS_ORIGINS");
    }

    @Test
    void acceptsExplicitHttpsProductionOrigins() {
        assertDoesNotThrow(() -> ProductionConfigurationValidator.validate(
                DB_URL, USERNAME, PASSWORD, JWT_SECRET, ORIGINS));
    }

    @Test
    void validationErrorsNeverIncludeSecretValues() {
        String sensitiveValue = "do-not-display-this-secret-value-012345";
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationValidator.validate(DB_URL, USERNAME, PASSWORD, sensitiveValue, "*"));

        assertTrue(error.getMessage().contains("APP_CORS_ORIGINS"));
        assertFalse(error.getMessage().contains(sensitiveValue));
        assertFalse(error.getMessage().contains(PASSWORD));
    }

    private void assertInvalid(String databaseUrl, String username, String password, String jwtSecret,
                               String origins, String expectedKey) {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationValidator.validate(databaseUrl, username, password, jwtSecret, origins));
        assertTrue(error.getMessage().contains(expectedKey));
    }
}
