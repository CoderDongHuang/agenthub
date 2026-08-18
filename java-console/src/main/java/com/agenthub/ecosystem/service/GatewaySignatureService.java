package com.agenthub.ecosystem.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class GatewaySignatureService {
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private final Clock clock;

    public GatewaySignatureService() {
        this(Clock.systemUTC());
    }

    GatewaySignatureService(Clock clock) {
        this.clock = clock;
    }

    public String sign(String secret, String method, String path, long timestamp, String nonce, byte[] body) {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("Developer secret is invalid");
        String canonical = canonical(method, path, timestamp, nonce, body);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign gateway request", exception);
        }
    }

    public boolean verify(String secret, String method, String path, long timestamp,
                          String nonce, byte[] body, String signature) {
        if (Math.abs(Instant.now(clock).getEpochSecond() - timestamp) > MAX_CLOCK_SKEW_SECONDS) return false;
        if (nonce == null || nonce.length() < 12 || nonce.length() > 120 || signature == null) return false;
        try {
            byte[] expected = Base64.getUrlDecoder().decode(sign(secret, method, path, timestamp, nonce, body));
            byte[] actual = Base64.getUrlDecoder().decode(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public String canonical(String method, String path, long timestamp, String nonce, byte[] body) {
        return method.toUpperCase() + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + sha256(body);
    }

    private String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body == null ? new byte[0] : body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
