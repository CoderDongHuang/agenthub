package com.agenthub.ecosystem.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;

@Service
public class ArtifactIntegrityService {
    private final byte[] signingRoot;
    private final ObjectMapper canonicalMapper;

    public ArtifactIntegrityService(@Value("${agenthub.ecosystem.signing-key}") String signingRoot,
                                    ObjectMapper objectMapper) {
        if (signingRoot == null || signingRoot.length() < 32) {
            throw new IllegalStateException("AGENTHUB_ECOSYSTEM_SIGNING_KEY or KMS root must contain at least 32 characters");
        }
        this.signingRoot = signingRoot.getBytes(StandardCharsets.UTF_8).clone();
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String digest(byte[] artifact) {
        if (artifact == null || artifact.length == 0) throw new IllegalArgumentException("Package artifact is required");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public String sign(long tenantId, String digest, Map<String, Object> manifest) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(tenantId, payload(digest, manifest)));
    }

    public boolean verify(long tenantId, String digest, Map<String, Object> manifest, String signature) {
        if (signature == null || signature.isBlank()) return false;
        try {
            byte[] expected = hmac(tenantId, payload(digest, manifest));
            byte[] actual = Base64.getUrlDecoder().decode(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public Map<String, Object> scan(String sourceUri, Map<String, Object> manifest,
                                    Map<String, Object> compatibility, boolean signatureValid) {
        List<Map<String, Object>> findings = new ArrayList<>();
        int risk = 0;
        String source = Objects.toString(sourceUri, "").trim().toLowerCase(Locale.ROOT);
        if (!(source.startsWith("https://") || source.startsWith("registry://") || source.startsWith("mcp://"))) {
            risk += 45;
            findings.add(finding("source", "high", "Package source must use HTTPS or an authenticated private registry"));
        }
        if (!signatureValid) {
            risk += 70;
            findings.add(finding("signature", "critical", "Package signature does not match its digest and manifest"));
        }
        Object dependencies = manifest.get("dependencies");
        if (dependencies instanceof Map<?, ?> dependencyMap) {
            for (Map.Entry<?, ?> entry : dependencyMap.entrySet()) {
                String version = Objects.toString(entry.getValue(), "").trim().toLowerCase(Locale.ROOT);
                if (version.isBlank() || version.equals("latest") || version.equals("*") || version.startsWith(">=")) {
                    risk += 12;
                    findings.add(finding("dependency", "medium", "Dependency " + entry.getKey() + " is not pinned"));
                }
            }
        }
        for (String permission : stringList(manifest.get("permissions"))) {
            String normalized = permission.toLowerCase(Locale.ROOT);
            if (normalized.equals("network:any") || normalized.equals("filesystem:write") || normalized.equals("process:spawn")) {
                risk += 20;
                findings.add(finding("permission", "high", "Broad permission requested: " + permission));
            }
        }
        String minimum = Objects.toString(compatibility.getOrDefault("minPlatformVersion", ""), "").trim();
        if (minimum.isBlank()) {
            risk += 8;
            findings.add(finding("compatibility", "low", "Minimum platform version is not declared"));
        }
        risk = Math.min(100, risk);
        String status = risk >= 70 ? "blocked" : risk >= 25 ? "warning" : "passed";
        return Map.of("status", status, "riskScore", risk, "signatureValid", signatureValid, "findings", findings);
    }

    public Map<String, Object> sandboxProfile(Map<String, Object> request) {
        int timeout = intValue(request.get("timeoutSeconds"), 30);
        int memory = intValue(request.get("memoryMb"), 256);
        double cpu = doubleValue(request.get("cpuCores"), 0.5);
        List<String> networkHosts = stringList(request.get("networkHosts"));
        List<String> mounts = stringList(request.get("mounts"));
        List<String> violations = new ArrayList<>();
        if (timeout < 1 || timeout > 120) violations.add("timeoutSeconds must be between 1 and 120");
        if (memory < 64 || memory > 1024) violations.add("memoryMb must be between 64 and 1024");
        if (cpu <= 0 || cpu > 2) violations.add("cpuCores must be greater than 0 and at most 2");
        if (networkHosts.stream().anyMatch(host -> host.equals("*") || host.contains("/"))) {
            violations.add("networkHosts must contain explicit host names only");
        }
        if (mounts.stream().anyMatch(path -> path.contains("..") || path.equals("/") || path.matches("^[A-Za-z]:.*"))) {
            violations.add("mount paths must be relative and cannot traverse outside the package workspace");
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("allowed", violations.isEmpty());
        profile.put("violations", violations);
        profile.put("runtime", "isolated-worker");
        profile.put("networkMode", networkHosts.isEmpty() ? "none" : "allowlist");
        profile.put("networkHosts", networkHosts);
        profile.put("readOnlyRootFilesystem", true);
        profile.put("noNewPrivileges", true);
        profile.put("seccompProfile", "RuntimeDefault");
        profile.put("timeoutSeconds", Math.max(1, Math.min(timeout, 120)));
        profile.put("memoryMb", Math.max(64, Math.min(memory, 1024)));
        profile.put("cpuCores", Math.max(0.1, Math.min(cpu, 2.0)));
        profile.put("mounts", mounts);
        return profile;
    }

    private byte[] hmac(long tenantId, byte[] payload) {
        try {
            Mac derive = Mac.getInstance("HmacSHA256");
            derive.init(new SecretKeySpec(signingRoot, "HmacSHA256"));
            byte[] tenantKey = derive.doFinal(("agenthub:ecosystem:" + tenantId).getBytes(StandardCharsets.UTF_8));
            Mac signer = Mac.getInstance("HmacSHA256");
            signer.init(new SecretKeySpec(tenantKey, "HmacSHA256"));
            return signer.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign package", exception);
        }
    }

    private byte[] payload(String digest, Map<String, Object> manifest) {
        try {
            return (digest + "\n" + canonicalMapper.writeValueAsString(new TreeMap<>(manifest)))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Package manifest is not valid JSON", exception);
        }
    }

    private Map<String, Object> finding(String category, String severity, String message) {
        return Map.of("category", category, "severity", severity, "message", message);
    }

    private List<String> stringList(Object raw) {
        if (!(raw instanceof Collection<?> values)) return List.of();
        return values.stream().map(String::valueOf).map(String::trim).filter(value -> !value.isBlank()).toList();
    }

    private int intValue(Object raw, int fallback) {
        try { return raw == null ? fallback : Integer.parseInt(String.valueOf(raw)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private double doubleValue(Object raw, double fallback) {
        try { return raw == null ? fallback : Double.parseDouble(String.valueOf(raw)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
