package com.fleettwin.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {

    private static SecurityProperties withSecret(String secret) {
        return new SecurityProperties(secret, Duration.ofMinutes(15), Duration.ofDays(7), "admin", "a-real-password", 10);
    }

    @Test
    void refusesAMissingShortOrExampleSecret() {
        assertThrows(IllegalArgumentException.class, () -> withSecret(null));
        assertThrows(IllegalArgumentException.class, () -> withSecret("too-short"));
        // long enough, but it is the value in .env.example, which anyone can read
        assertThrows(IllegalArgumentException.class, () -> withSecret("change-me-to-at-least-32-random-characters"));
        assertDoesNotThrow(() -> withSecret("k3Jq8mZ1vT5xW9bN2cR7yU4pL6sD0fGh"));
    }
}
