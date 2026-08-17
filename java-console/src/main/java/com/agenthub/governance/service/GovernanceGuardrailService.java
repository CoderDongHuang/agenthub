package com.agenthub.governance.service;

import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GovernanceGuardrailService {
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore|forget|override).{0,24}(previous|system|developer).{0,18}(instruction|prompt)|" +
            "(reveal|print|show).{0,18}(system prompt|hidden instruction)|" +
            "developer\\s*mode|jailbreak|越狱|忽略.{0,12}(之前|系统).{0,12}(指令|提示词)|泄露.{0,8}(系统|提示词)");
    private static final Pattern EMAIL = Pattern.compile("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern SHELL_META = Pattern.compile("(;|&&|\\|\\||`|\\$\\(|\\r|\\n)");
    private static final Pattern SQL_META = Pattern.compile("(?i)(--|/\\*|;\\s*(drop|delete|update|insert)|\\bunion\\s+select\\b)");
    private static final List<String> BLOCKED_EXTENSIONS = List.of(
            ".exe", ".dll", ".bat", ".cmd", ".ps1", ".js", ".vbs", ".jar", ".msi", ".docm", ".xlsm", ".pptm");

    public Map<String, Object> scan(Map<String, Object> request) {
        List<Finding> findings = new ArrayList<>();
        String text = string(request.get("text"));
        Matcher injection = INJECTION.matcher(text);
        if (injection.find()) findings.add(new Finding("prompt_injection", "critical", injection.group(), true));
        collectPii(text, findings);
        scanFile(request, findings);
        scanToolParameters(asMap(request.get("toolParameters")), findings);

        boolean blocked = findings.stream().anyMatch(Finding::blocking);
        String redacted = redact(text);
        return Map.of(
                "allowed", !blocked,
                "action", blocked ? "block" : (redacted.equals(text) ? "allow" : "redact"),
                "redactedText", redacted,
                "findings", findings,
                "layersChecked", List.of("prompt_injection", "sensitive_data", "malicious_file", "tool_parameters")
        );
    }

    private void collectPii(String text, List<Finding> findings) {
        addMatches(EMAIL, text, "email", findings);
        addMatches(PHONE, text, "phone", findings);
        addMatches(ID_CARD, text, "id_card", findings);
        addMatches(BANK_CARD, text, "bank_card", findings);
    }

    private void addMatches(Pattern pattern, String text, String type, List<Finding> findings) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) findings.add(new Finding(type, "medium", mask(matcher.group()), false));
    }

    private void scanFile(Map<String, Object> request, List<Finding> findings) {
        String name = string(request.get("fileName")).toLowerCase();
        if (!name.isBlank() && BLOCKED_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            findings.add(new Finding("malicious_file", "critical", "blocked extension: " + name, true));
        }
        String encoded = string(request.get("fileBase64"));
        if (encoded.isBlank()) return;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > 25 * 1024 * 1024) {
                findings.add(new Finding("malicious_file", "critical", "file exceeds 25 MB", true));
            }
            if (bytes.length >= 2 && bytes[0] == 'M' && bytes[1] == 'Z') {
                findings.add(new Finding("malicious_file", "critical", "executable file signature", true));
            }
        } catch (IllegalArgumentException exception) {
            findings.add(new Finding("malicious_file", "high", "invalid base64 payload", true));
        }
    }

    private void scanToolParameters(Map<String, Object> parameters, List<Finding> findings) {
        parameters.forEach((key, raw) -> {
            String value = string(raw);
            if (SHELL_META.matcher(value).find() || SQL_META.matcher(value).find() || value.contains("../") || value.contains("..\\")) {
                findings.add(new Finding("unsafe_tool_parameter", "critical", key + ": unsafe expression", true));
            }
            if ((key.toLowerCase().contains("url") || value.startsWith("http://") || value.startsWith("https://"))
                    && isPrivateUrl(value)) {
                findings.add(new Finding("ssrf", "critical", key + ": private network target", true));
            }
        });
    }

    private boolean isPrivateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return true;
            String host = uri.getHost();
            if (host == null) return true;
            InetAddress address = InetAddress.getByName(host);
            return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || address.isMulticastAddress();
        } catch (Exception exception) {
            return true;
        }
    }

    private String redact(String value) {
        String result = EMAIL.matcher(value).replaceAll("[EMAIL]");
        result = PHONE.matcher(result).replaceAll("[PHONE]");
        result = ID_CARD.matcher(result).replaceAll("[ID_CARD]");
        return BANK_CARD.matcher(result).replaceAll("[BANK_CARD]");
    }

    private String mask(String value) {
        if (value.length() <= 4) return "****";
        return value.substring(0, 2) + "***" + value.substring(value.length() - 2);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record Finding(String type, String severity, String evidence, boolean blocking) {}
}
