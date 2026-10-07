package com.fleettwin.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code fleet.security}. Kept apart from FleetProperties, which the settings endpoint returns as is.
 * adminUsername / adminPassword create the first admin when the users table is empty.
 */
@ConfigurationProperties(prefix = "fleet.security")
public record SecurityProperties(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl,
                                 String adminUsername, String adminPassword, int minPasswordLength) {

    /** What the example .env files ship with. Anyone can read those, so a secret left like that is no secret. */
    static final String PLACEHOLDER = "change-me";

    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET must be set to at least 32 characters (e.g. openssl rand -base64 48)");
        }
        if (jwtSecret.startsWith(PLACEHOLDER)) {
            throw new IllegalArgumentException("JWT_SECRET still has its example value; replace it (e.g. openssl rand -base64 48)");
        }
    }
}
