package com.agenthub.governance.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class TenantKeyService {
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;
    private final byte[] masterKey;
    private final SecureRandom random = new SecureRandom();

    public TenantKeyService(@Value("${agenthub.kms.master-key}") String masterKey) {
        if (masterKey == null || masterKey.length() < 32) {
            throw new IllegalStateException("AGENTHUB_KMS_MASTER_KEY or JWT_SECRET must contain at least 32 characters");
        }
        this.masterKey = masterKey.getBytes(StandardCharsets.UTF_8).clone();
    }

    public EncryptedValue encrypt(long tenantId, int version, String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Secret value is required");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, tenantKey(tenantId, version), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, version));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return new EncryptedValue(Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(nonce), version);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt tenant secret", exception);
        }
    }

    public String decrypt(long tenantId, EncryptedValue value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = Base64.getDecoder().decode(value.nonce());
            cipher.init(Cipher.DECRYPT_MODE, tenantKey(tenantId, value.keyVersion()),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, value.keyVersion()));
            return new String(cipher.doFinal(Base64.getDecoder().decode(value.ciphertext())), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new SecurityException("Tenant secret integrity verification failed", exception);
        }
    }

    private SecretKeySpec tenantKey(long tenantId, int version) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(masterKey, "HmacSHA256"));
        byte[] derived = mac.doFinal(("agenthub:kms:" + tenantId + ":" + version)
                .getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(derived, "AES");
    }

    private byte[] aad(long tenantId, int version) {
        return (tenantId + ":" + version).getBytes(StandardCharsets.UTF_8);
    }

    public record EncryptedValue(String ciphertext, String nonce, int keyVersion) {}
}
