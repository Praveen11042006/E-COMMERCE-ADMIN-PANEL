package com.examly.springapp.config;

import com.examly.springapp.utils.JwtSecretValidator;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/** Fails fast when the production profile receives unsafe or incomplete configuration. */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements BeanFactoryPostProcessor {

    private final String databaseUrl;
    private final String databaseUsername;
    private final String databasePassword;
    private final String jwtSecret;
    private final String corsOrigins;

    public ProductionConfigurationValidator(
            @Value("${spring.datasource.url}") String databaseUrl,
            @Value("${spring.datasource.username}") String databaseUsername,
            @Value("${spring.datasource.password}") String databasePassword,
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${app.cors.allowed-origins}") String corsOrigins) {
        this.databaseUrl = databaseUrl;
        this.databaseUsername = databaseUsername;
        this.databasePassword = databasePassword;
        this.jwtSecret = jwtSecret;
        this.corsOrigins = corsOrigins;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        validate(databaseUrl, databaseUsername, databasePassword, jwtSecret, corsOrigins);
    }

    public static void validate(String databaseUrl, String databaseUsername, String databasePassword,
                                String jwtSecret, String corsOrigins) {
        requireNonblank("DB_URL", databaseUrl);
        requireNonblank("DB_USERNAME", databaseUsername);
        requireNonblank("DB_PASSWORD", databasePassword);
        requireNonblank("JWT_SECRET", jwtSecret);
        requireNonblank("APP_CORS_ORIGINS", corsOrigins);

        if (!databaseUrl.startsWith("jdbc:mysql:")) {
            throw invalid("DB_URL must use the jdbc:mysql: scheme");
        }

        JwtSecretValidator.requireMinimumLength(jwtSecret);
        validateCorsOrigins(corsOrigins);
    }

    private static void validateCorsOrigins(String corsOrigins) {
        String[] origins = corsOrigins.split(",", -1);
        for (String origin : origins) {
            if (origin.isBlank()) {
                throw invalid("APP_CORS_ORIGINS must not contain blank origins");
            }
            if (!origin.equals(origin.trim())) {
                throw invalid("APP_CORS_ORIGINS must not contain whitespace-padded origins");
            }
            if (origin.equals("*") || origin.equalsIgnoreCase("null") || origin.contains("*")) {
                throw invalid("APP_CORS_ORIGINS must contain explicit origins only");
            }

            URI uri;
            try {
                uri = new URI(origin);
            } catch (URISyntaxException e) {
                throw invalid("APP_CORS_ORIGINS contains a malformed origin");
            }

            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
                throw invalid("APP_CORS_ORIGINS must contain explicit HTTPS origins only");
            }

            String host = uri.getHost();
            if (host.equalsIgnoreCase("localhost") || host.endsWith(".localhost")
                    || host.equals("127.0.0.1") || host.equals("::1")) {
                throw invalid("APP_CORS_ORIGINS must not contain localhost origins");
            }
        }
    }

    private static void requireNonblank(String key, String value) {
        if (value == null || value.isBlank()) {
            throw invalid(key + " must be set and nonblank");
        }
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Production configuration error: " + detail);
    }
}
