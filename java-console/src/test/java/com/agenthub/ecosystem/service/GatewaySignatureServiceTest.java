package com.agenthub.ecosystem.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class GatewaySignatureServiceTest {
    private static final String SECRET = "developer-secret-at-least-32-characters-long";
    private static final long NOW = 1_800_000_000L;
    private final GatewaySignatureService service = new GatewaySignatureService(
            Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    @Test
    void verifiesCanonicalRequestAndRejectsTampering() {
        byte[] body = "{\"operation\":\"platform.echo\"}".getBytes(StandardCharsets.UTF_8);
        String signature = service.sign(SECRET, "POST", "/api/gateway/v1/invoke", NOW,
                "nonce-123456789", body);

        assertTrue(service.verify(SECRET, "POST", "/api/gateway/v1/invoke", NOW,
                "nonce-123456789", body, signature));
        assertFalse(service.verify(SECRET, "POST", "/api/gateway/v1/invoke", NOW,
                "nonce-123456789", "{}".getBytes(StandardCharsets.UTF_8), signature));
    }

    @Test
    void rejectsExpiredTimestampAndShortNonce() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String oldSignature = service.sign(SECRET, "POST", "/api/gateway/v1/invoke", NOW - 600,
                "nonce-123456789", body);
        String shortNonceSignature = service.sign(SECRET, "POST", "/api/gateway/v1/invoke", NOW,
                "short", body);

        assertFalse(service.verify(SECRET, "POST", "/api/gateway/v1/invoke", NOW - 600,
                "nonce-123456789", body, oldSignature));
        assertFalse(service.verify(SECRET, "POST", "/api/gateway/v1/invoke", NOW,
                "short", body, shortNonceSignature));
    }
}
