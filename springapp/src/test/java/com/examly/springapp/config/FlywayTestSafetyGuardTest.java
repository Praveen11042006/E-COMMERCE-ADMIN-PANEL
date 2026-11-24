package com.examly.springapp.config;

import com.examly.springapp.ECommerceAdminPanelApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlywayTestSafetyGuardTest {

    @Test
    void defaultAndH2TestConfigurationsKeepFlywayDisabled() throws IOException {
        assertEquals("false", properties("src/main/resources/application.properties").getProperty("spring.flyway.enabled"));
        assertEquals("false", properties("src/test/resources/application.properties").getProperty("spring.flyway.enabled"));
    }

    @Test
    void productionConfigurationKeepsFlywayDisabled() throws IOException {
        assertEquals("false", properties("src/main/resources/application-prod.properties").getProperty("spring.flyway.enabled"));
    }

    @Test
    void isolatedFlywayTestConfigurationExplicitlyEnablesFlyway() throws IOException {
        Properties properties = properties("src/main/resources/application-flyway-test.properties");
        assertEquals("${FLYWAY_TEST_DB_URL}", properties.getProperty("spring.datasource.url"));
        assertEquals("${FLYWAY_TEST_DB_USERNAME}", properties.getProperty("spring.datasource.username"));
        assertEquals("${FLYWAY_TEST_DB_PASSWORD}", properties.getProperty("spring.datasource.password"));
        assertEquals("true", properties.getProperty("spring.flyway.enabled"));
        assertEquals("classpath:db/migration", properties.getProperty("spring.flyway.locations"));
        assertEquals("false", properties.getProperty("spring.flyway.baseline-on-migrate"));
        assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"));
    }

    @Test
    void acceptsOnlyTheDedicatedFlywayTestTarget() {
        assertDoesNotThrow(() -> FlywayTestSafetyGuard.validate(validEnvironment()));
    }

    @Test
    void rejectsProdCombinedWithFlywayTestBeforeMigrationBeansExist() {
        MockEnvironment environment = validEnvironment();
        environment.setActiveProfiles("prod", "flyway-test");
        assertRejected(environment);
    }

    @Test
    void registeredEnvironmentGuardRejectsUnsafeProfileCombinationBeforeApplicationStartup() {
        assertThrows(IllegalStateException.class, () -> new SpringApplicationBuilder(ECommerceAdminPanelApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("prod", "flyway-test")
                .run());
    }

    @Test
    void rejectsOriginalDatabaseTarget() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/ecom");
        assertRejected(environment);
    }

    @Test
    void rejectsUnexpectedDatabaseUsername() {
        MockEnvironment environment = validEnvironment();
        environment.setProperty("spring.datasource.username", "root");
        assertRejected(environment);
    }

    @Test
    void baselineMigrationContainsOnlyTheApprovedSevenTableDefinitions() throws IOException {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V1__baseline_schema.sql"));
        assertEquals(7, sql.split("(?i)CREATE TABLE", -1).length - 1);
        assertFalse(sql.matches("(?is).*\\b(DROP|TRUNCATE|DELETE|INSERT)\\b.*"));
    }

    private MockEnvironment validEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("flyway-test");
        environment.setProperty("spring.flyway.enabled", "true");
        environment.setProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/ecom_flyway_test");
        environment.setProperty("spring.datasource.username", "flyway_test");
        environment.setProperty("spring.datasource.password", "not-printed");
        return environment;
    }

    private void assertRejected(MockEnvironment environment) {
        environment.addActiveProfile("flyway-test");
        assertThrows(IllegalStateException.class, () -> FlywayTestSafetyGuard.validate(environment));
    }

    private Properties properties(String relativePath) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(Path.of(relativePath))) {
            properties.load(stream);
        }
        return properties;
    }
}
