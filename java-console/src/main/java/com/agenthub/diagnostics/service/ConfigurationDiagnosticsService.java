package com.agenthub.diagnostics.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.*;

@Service
public class ConfigurationDiagnosticsService {

    private static final List<String> MODEL_KEYS = List.of(
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "DEEPSEEK_API_KEY", "GEMINI_API_KEY",
            "QWEN_API_KEY", "KIMI_API_KEY", "ZHIPU_API_KEY", "MINIMAX_API_KEY");

    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;
    private final Environment environment;
    private final RestClient runtime;

    public ConfigurationDiagnosticsService(JdbcTemplate jdbc, RedisConnectionFactory redis,
                                           Environment environment,
                                           @Value("${python.runtime.base-url}") String runtimeBaseUrl) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.environment = environment;
        this.runtime = RestClient.builder().baseUrl(runtimeBaseUrl).build();
    }

    public Map<String, Object> diagnose(Long tenantId) {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(databaseCheck());
        checks.add(redisCheck());
        checks.add(runtimeCheck());
        checks.add(modelCredentialsCheck());
        checks.add(secretCheck("JWT_SECRET", 32, "JWT signing secret"));
        checks.add(secretCheck("AGENTHUB_INTERNAL_TOKEN", 32, "Java/Python internal service token"));
        checks.add(corsCheck());
        checks.addAll(channelChecks());
        checks.add(modelRoutingCheck(tenantId));

        long passed = checks.stream().filter(item -> "pass".equals(item.get("status"))).count();
        long failed = checks.stream().filter(item -> "fail".equals(item.get("status"))).count();
        long warnings = checks.stream().filter(item -> "warning".equals(item.get("status"))).count();
        return Map.of(
                "status", failed > 0 ? "action_required" : warnings > 0 ? "attention" : "ready",
                "summary", Map.of("total", checks.size(), "passed", passed, "warnings", warnings, "failed", failed),
                "checks", checks,
                "secretsExposed", false
        );
    }

    private Map<String, Object> databaseCheck() {
        try {
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            return check("database", "PostgreSQL", Objects.equals(value, 1) ? "pass" : "fail",
                    "Database query completed", "Verify DB_HOST, DB_PORT, DB_NAME, DB_USER and DB_PASSWORD");
        } catch (Exception exception) {
            return check("database", "PostgreSQL", "fail", safe(exception),
                    "Start PostgreSQL and verify DB_* settings");
        }
    }

    private Map<String, Object> redisCheck() {
        try (var connection = redis.getConnection()) {
            String pong = connection.ping();
            return check("redis", "Redis", "PONG".equalsIgnoreCase(pong) ? "pass" : "warning",
                    "Redis ping: " + Objects.requireNonNullElse(pong, "no response"), "Verify REDIS_URL");
        } catch (Exception exception) {
            return check("redis", "Redis", "fail", safe(exception), "Start Redis and verify REDIS_URL");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runtimeCheck() {
        try {
            Map<String, Object> response = runtime.get().uri("/health").retrieve().body(Map.class);
            boolean up = isHealthyResponse(response);
            return check("runtime", "Python runtime", up ? "pass" : "warning",
                    up ? "Runtime health endpoint is healthy" : "Runtime responded without healthy status",
                    "Start python-engine on PYTHON_RUNTIME_URL");
        } catch (Exception exception) {
            return check("runtime", "Python runtime", "fail", safe(exception),
                    "Start python-engine and verify PYTHON_RUNTIME_URL");
        }
    }

    static boolean isHealthyResponse(Map<String, Object> response) {
        if (response == null) return false;
        String status = String.valueOf(response.get("status")).trim().toLowerCase(Locale.ROOT);
        return Set.of("up", "healthy", "ok").contains(status);
    }

    private Map<String, Object> modelCredentialsCheck() {
        List<String> configuredProviders = MODEL_KEYS.stream().filter(this::configured)
                .map(key -> key.substring(0, key.indexOf("_API_KEY")).toLowerCase(Locale.ROOT)).toList();
        String demo = value("AGENTHUB_DEMO_MODE");
        if (!configuredProviders.isEmpty()) {
            return checkWithMetadata("model_credentials", "Model credentials", "pass",
                    configuredProviders.size() + " provider credential(s) configured", "No secret values are returned",
                    Map.of("providers", configuredProviders));
        }
        if ("true".equalsIgnoreCase(demo)) {
            return check("model_credentials", "Model credentials", "warning", "Demo mode is active",
                    "Configure at least one *_API_KEY before production testing");
        }
        return check("model_credentials", "Model credentials", "fail", "No supported model credential found",
                "Configure OPENAI_API_KEY, ANTHROPIC_API_KEY, DEEPSEEK_API_KEY or another supported provider key");
    }

    private Map<String, Object> secretCheck(String key, int minLength, String title) {
        String raw = value(key);
        boolean valid = raw != null && raw.length() >= minLength && !raw.toLowerCase(Locale.ROOT).contains("change");
        return check(key.toLowerCase(Locale.ROOT), title, valid ? "pass" : "fail",
                valid ? "Configured with acceptable minimum length" : "Missing, placeholder or too short",
                "Set " + key + " to a random value of at least " + minLength + " characters");
    }

    private Map<String, Object> corsCheck() {
        String origins = value("AGENTHUB_CORS_ORIGINS");
        if (origins == null) origins = "http://localhost:5173,http://127.0.0.1:5173";
        boolean wildcard = Arrays.stream(origins.split(",")).anyMatch(item -> item.trim().equals("*"));
        return checkWithMetadata("cors", "CORS origins", wildcard ? "fail" : "pass",
                wildcard ? "Wildcard origin is not allowed with credentials" : "Explicit origins configured",
                "Keep local origins for local-only use and add exact domains when deploying",
                Map.of("originCount", origins.split(",").length));
    }

    private List<Map<String, Object>> channelChecks() {
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(channel("dingtalk", "DingTalk", List.of("DINGTALK_WEBHOOK_URL", "DINGTALK_SIGN_SECRET"),
                List.of("Robot outbound webhook; inbound Stream mode or application event subscription")));
        checks.add(channel("feishu", "Feishu", List.of("FEISHU_APP_ID", "FEISHU_APP_SECRET", "FEISHU_ENCRYPT_KEY", "FEISHU_VERIFICATION_TOKEN"),
                List.of("im:message.receive_v1", "im:message", "im:message:send_as_bot")));
        checks.add(channel("wechat", "WeCom", List.of("WECHAT_CORP_ID", "WECHAT_AGENT_ID", "WECHAT_APP_SECRET", "WECHAT_TOKEN", "WECHAT_ENCODING_AES_KEY"),
                List.of("Message receive API", "Send application messages", "Enterprise-owned callback domain")));
        return checks;
    }

    private Map<String, Object> channel(String id, String title, List<String> keys, List<String> requirements) {
        List<String> missing = keys.stream().filter(key -> !configured(key)).toList();
        String callback = value(id.equals("wechat") ? "WECHAT_CALLBACK_URL" : id.toUpperCase(Locale.ROOT) + "_CALLBACK_URL");
        boolean callbackValid = callback != null && validHttps(callback);
        String status = missing.isEmpty() ? (callback == null || callback.isBlank() ? "warning" : callbackValid ? "pass" : "fail") : "warning";
        String detail = missing.isEmpty() ? (callback == null || callback.isBlank()
                ? "Credentials configured; inbound callback is intentionally unset"
                : callbackValid ? "Credentials and HTTPS callback configured" : "Callback URL is invalid")
                : missing.size() + " required setting(s) missing";
        return checkWithMetadata("channel_" + id, title, status, detail,
                "Confirm event subscriptions and platform permissions in the provider console",
                Map.of("missingKeys", missing, "requirements", requirements, "callbackConfigured", callback != null && !callback.isBlank()));
    }

    private Map<String, Object> modelRoutingCheck(Long tenantId) {
        Map<String, Object> stats = jdbc.queryForMap(
                "SELECT COUNT(*) AS endpoints,COUNT(*) FILTER (WHERE status='healthy') AS healthy," +
                        "COUNT(*) FILTER (WHERE circuit_state='open') AS open_circuits FROM model_endpoint WHERE tenant_id=?",
                tenantId);
        int endpoints = ((Number) stats.get("endpoints")).intValue();
        int healthy = ((Number) stats.get("healthy")).intValue();
        return checkWithMetadata("model_routing", "Model routing and health", endpoints == 0 ? "warning" : healthy == 0 ? "warning" : "pass",
                endpoints == 0 ? "No managed model endpoints" : healthy + " of " + endpoints + " endpoints healthy",
                "Configure endpoint metadata and run a health probe", stats);
    }

    private Map<String, Object> check(String id, String title, String status, String detail, String action) {
        return checkWithMetadata(id, title, status, detail, action, Map.of());
    }

    private Map<String, Object> checkWithMetadata(String id, String title, String status, String detail,
                                                   String action, Object metadata) {
        return Map.of("id", id, "title", title, "status", status, "detail", detail,
                "action", action, "metadata", metadata);
    }

    private boolean configured(String key) {
        String raw = value(key);
        return raw != null && !raw.isBlank() && !raw.contains("your-") && !raw.contains("change-me");
    }

    private String value(String key) {
        String value = environment.getProperty(key);
        return value == null ? null : value.trim();
    }

    private boolean validHttps(String raw) {
        try {
            URI uri = URI.create(raw);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (Exception ignored) { return false; }
    }

    private String safe(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null ? "" : ": " +
                (message.length() > 160 ? message.substring(0, 160) : message));
    }
}
