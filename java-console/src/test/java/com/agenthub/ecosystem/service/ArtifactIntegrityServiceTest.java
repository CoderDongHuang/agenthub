package com.agenthub.ecosystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArtifactIntegrityServiceTest {
    private final ArtifactIntegrityService service = new ArtifactIntegrityService(
            "test-ecosystem-signing-root-at-least-32-characters", new ObjectMapper());

    @Test
    void signsDigestAndCanonicalManifest() {
        byte[] artifact = "signed-tool-package".getBytes(StandardCharsets.UTF_8);
        String digest = service.digest(artifact);
        Map<String, Object> manifest = Map.of("name", "crm.lookup", "permissions", List.of("network:crm.example.com"));
        String signature = service.sign(7, digest, manifest);

        assertTrue(service.verify(7, digest, manifest, signature));
        assertFalse(service.verify(7, service.digest("tampered".getBytes(StandardCharsets.UTF_8)), manifest, signature));
        assertFalse(service.verify(8, digest, manifest, signature));
    }

    @Test
    void supplyChainScanBlocksInvalidSignatureAndMutableDependencies() {
        Map<String, Object> scan = service.scan("http://insecure.example/tool.zip",
                Map.of("dependencies", Map.of("requests", "latest"),
                        "permissions", List.of("network:any", "process:spawn")),
                Map.of(), false);

        assertEquals("blocked", scan.get("status"));
        assertEquals(false, scan.get("signatureValid"));
        assertTrue(((Number) scan.get("riskScore")).intValue() >= 70);
    }

    @Test
    void sandboxDefaultsToNoNetworkAndRejectsTraversal() {
        Map<String, Object> accepted = service.sandboxProfile(Map.of(
                "timeoutSeconds", 20, "memoryMb", 256, "networkHosts", List.of("crm.example.com")));
        Map<String, Object> rejected = service.sandboxProfile(Map.of(
                "mounts", List.of("../../host"), "networkHosts", List.of("*"), "timeoutSeconds", 500));

        assertEquals(true, accepted.get("allowed"));
        assertEquals(true, accepted.get("readOnlyRootFilesystem"));
        assertEquals(false, rejected.get("allowed"));
        assertFalse(((List<?>) rejected.get("violations")).isEmpty());
    }
}
