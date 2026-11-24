package com.examly.springapp.utils;

import java.nio.charset.StandardCharsets;

/** Shared JWT key validation used by startup configuration and token signing. */
public final class JwtSecretValidator {

    private JwtSecretValidator() {
    }

    public static byte[] requireMinimumLength(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET must be set and nonblank");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        return keyBytes;
    }
}
