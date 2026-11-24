package com.examly.springapp.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Phase 16 — the base profile must never imply a database.
 *
 * <p>Regression guard for the Phase 13E/Phase 15 finding: a bare start used to
 * default to {@code jdbc:mysql://localhost:3306/ecom} with {@code ddl-auto=update}.
 * Now a database is required explicitly and only the explicit {@code dev} /
 * demo {@code docker} profiles may change the schema. No database is contacted
 * by these tests; they read the configuration files only.
 */
class SafeDatabaseConfigurationTest {

    @Test
    void baseProfileHasNoImplicitDatabaseOrSchemaMutation() throws IOException {
        Properties base = properties("src/main/resources/application.properties");

        assertEquals("${DB_URL}", base.getProperty("spring.datasource.url"));
        assertEquals("${DB_USERNAME}", base.getProperty("spring.datasource.username"));
        assertEquals("${DB_PASSWORD}", base.getProperty("spring.datasource.password"));
        assertEquals("validate", base.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", base.getProperty("spring.flyway.enabled"));

        // The populated local database must not appear as a fallback anywhere.
        assertFalse(base.getProperty("spring.datasource.url").contains("jdbc:mysql"));
        assertFalse(base.getProperty("spring.datasource.url").contains("ecom?"));
    }

    @Test
    void productionProfileStaysValidateOnlyWithFlywayDisabled() throws IOException {
        Properties prod = properties("src/main/resources/application-prod.properties");

        assertEquals("${DB_URL}", prod.getProperty("spring.datasource.url"));
        assertEquals("validate", prod.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", prod.getProperty("spring.flyway.enabled"));
    }

    @Test
    void onlyExplicitLocalAndDemoProfilesMayUpdateTheSchema() throws IOException {
        assertEquals("update",
                properties("src/main/resources/application-dev.properties").getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("update",
                properties("src/main/resources/application-docker.properties").getProperty("spring.jpa.hibernate.ddl-auto"));
    }

    @Test
    void testAndE2eProfilesStayOnIsolatedInMemoryH2() throws IOException {
        Properties test = properties("src/test/resources/application.properties");
        assertEquals("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1", test.getProperty("spring.datasource.url"));
        assertEquals("create", test.getProperty("spring.jpa.hibernate.ddl-auto"));

        Properties e2e = properties("src/test/resources/application-e2e.properties");
        assertEquals("jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1;MODE=MySQL", e2e.getProperty("spring.datasource.url"));
        assertEquals("create", e2e.getProperty("spring.jpa.hibernate.ddl-auto"));
    }

    private Properties properties(String relativePath) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(Path.of(relativePath))) {
            properties.load(stream);
        }
        return properties;
    }
}
