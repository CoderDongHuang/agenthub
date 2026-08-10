package com.agenthub.platform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookUrlValidatorTest {
    private final WebhookUrlValidator validator = new WebhookUrlValidator();

    @Test
    void rejectsPlainHttp() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate("http://example.com/hook"));
    }

    @Test
    void rejectsLoopbackAndLocalhost() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate("https://127.0.0.1/hook"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("https://localhost/hook"));
    }

    @Test
    void rejectsUnexpectedPortAndUserInfo() {
        assertThrows(IllegalArgumentException.class, () -> validator.validate("https://example.com:8443/hook"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("https://user@example.com/hook"));
    }
}
