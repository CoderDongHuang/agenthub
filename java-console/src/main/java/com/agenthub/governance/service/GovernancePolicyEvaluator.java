package com.agenthub.governance.service;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class GovernancePolicyEvaluator {

    public AccessDecision evaluateAccess(String resourceType, String action, Map<String, Object> attributes,
                                         List<Map<String, Object>> policies) {
        for (Map<String, Object> policy : policies) {
            if (!Boolean.parseBoolean(String.valueOf(policy.getOrDefault("enabled", true)))) continue;
            if (!matches(String.valueOf(policy.getOrDefault("resourceType", "*")), resourceType)) continue;
            if (!matches(String.valueOf(policy.getOrDefault("actionPattern", "*")), action)) continue;
            if (!conditionsMatch(asMap(policy.get("conditions")), attributes)) continue;
            String effect = String.valueOf(policy.getOrDefault("effect", "deny"));
            return new AccessDecision("allow".equals(effect), effect, longValue(policy.get("id")),
                    String.valueOf(policy.getOrDefault("name", "matched policy")));
        }
        return new AccessDecision(false, "deny", null, "default deny");
    }

    public ApprovalDecision evaluateApproval(Map<String, Object> context, List<Map<String, Object>> policies) {
        for (Map<String, Object> policy : policies) {
            if (!Boolean.parseBoolean(String.valueOf(policy.getOrDefault("enabled", true)))) continue;
            if (!conditionsMatch(asMap(policy.get("conditions")), context)) continue;
            return new ApprovalDecision(String.valueOf(policy.getOrDefault("decision", "single")),
                    longValue(policy.get("id")), integer(policy.getOrDefault("slaMinutes", 60)),
                    String.valueOf(policy.getOrDefault("name", "matched policy")));
        }
        return new ApprovalDecision("single", null, 60, "default approval policy");
    }

    boolean conditionsMatch(Map<String, Object> conditions, Map<String, Object> attributes) {
        for (Map.Entry<String, Object> entry : conditions.entrySet()) {
            String key = entry.getKey();
            Object expected = entry.getValue();
            Object actual = attributes.get(key);
            if (key.endsWith("Min")) {
                actual = attributes.get(key.substring(0, key.length() - 3));
                if (decimal(actual).compareTo(decimal(expected)) < 0) return false;
            } else if (key.endsWith("Max")) {
                actual = attributes.get(key.substring(0, key.length() - 3));
                if (decimal(actual).compareTo(decimal(expected)) > 0) return false;
            } else if ("timeStart".equals(key) || "timeEnd".equals(key)) {
                LocalTime now = LocalTime.parse(String.valueOf(attributes.getOrDefault("time", LocalTime.now())));
                LocalTime boundary = LocalTime.parse(String.valueOf(expected));
                if ("timeStart".equals(key) && now.isBefore(boundary)) return false;
                if ("timeEnd".equals(key) && now.isAfter(boundary)) return false;
            } else if (expected instanceof List<?> list) {
                if (list.isEmpty()) continue;
                if (actual instanceof List<?> actualList) {
                    if (actualList.stream().noneMatch(list::contains)) return false;
                } else if (!list.contains(actual)) return false;
            } else if (!Objects.equals(String.valueOf(expected), String.valueOf(actual))) {
                return false;
            }
        }
        return true;
    }

    private boolean matches(String pattern, String actual) {
        return "*".equals(pattern) || pattern.equals(actual)
                || (pattern.endsWith("*") && actual.startsWith(pattern.substring(0, pattern.length() - 1)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private BigDecimal decimal(Object value) {
        try { return new BigDecimal(String.valueOf(value)); }
        catch (Exception ignored) { return BigDecimal.ZERO; }
    }

    private int integer(Object value) {
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception ignored) { return 60; }
    }

    private Long longValue(Object value) {
        if (value == null) return null;
        try { return Long.valueOf(String.valueOf(value)); }
        catch (Exception ignored) { return null; }
    }

    public record AccessDecision(boolean allowed, String effect, Long policyId, String reason) {}
    public record ApprovalDecision(String decision, Long policyId, int slaMinutes, String reason) {}
}
