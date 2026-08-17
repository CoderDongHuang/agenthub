package com.agenthub.governance.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantKeyServiceTest {
    private final TenantKeyService keys = new TenantKeyService("test-master-key-with-at-least-32-characters");

    @Test
    void encryptsAndDecryptsWithinTenantAndVersion() {
        TenantKeyService.EncryptedValue encrypted = keys.encrypt(7L, 1, "supplier-secret-value");

        assertNotEquals("supplier-secret-value", encrypted.ciphertext());
        assertEquals("supplier-secret-value", keys.decrypt(7L, encrypted));
        assertEquals(1, encrypted.keyVersion());
    }

    @Test
    void rejectsCrossTenantAndTamperedCiphertext() {
        TenantKeyService.EncryptedValue encrypted = keys.encrypt(7L, 1, "tenant-seven-secret");
        assertThrows(SecurityException.class, () -> keys.decrypt(8L, encrypted));

        TenantKeyService.EncryptedValue tampered = new TenantKeyService.EncryptedValue(
                encrypted.ciphertext().substring(0, encrypted.ciphertext().length() - 2) + "AA",
                encrypted.nonce(), encrypted.keyVersion());
        assertThrows(SecurityException.class, () -> keys.decrypt(7L, tampered));
    }

    @Test
    void versionedKeysSupportRotationWithoutChangingPlaintext() {
        TenantKeyService.EncryptedValue oldValue = keys.encrypt(3L, 1, "rotate-me");
        String plain = keys.decrypt(3L, oldValue);
        TenantKeyService.EncryptedValue newValue = keys.encrypt(3L, 2, plain);

        assertEquals("rotate-me", keys.decrypt(3L, newValue));
        assertNotEquals(oldValue.ciphertext(), newValue.ciphertext());
        assertThrows(SecurityException.class, () -> keys.decrypt(3L,
                new TenantKeyService.EncryptedValue(newValue.ciphertext(), newValue.nonce(), 1)));
    }
}
