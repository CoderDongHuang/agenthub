package com.agenthub.channel;

import com.agenthub.common.response.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import com.agenthub.security.CurrentUser;

@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {

    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public ApiKeyController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> keys = jdbc.queryForList(
                "SELECT id, key_name, agent_id, rate_limit, is_active, last_used_at, created_at " +
                        "FROM api_key WHERE tenant_id = ? ORDER BY created_at DESC", currentUser.tenantId()
        );
        return ApiResponse.ok(keys);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String keyName = body.getOrDefault("keyName", "API Key");
        String apiKey = "ak-" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Long agentId = body.containsKey("agentId") ? Long.valueOf(body.get("agentId")) : null;

        jdbc.update(
                "INSERT INTO api_key (tenant_id, key_name, api_key, agent_id, user_id, rate_limit) " +
                        "VALUES (?,?,?,?,?,1000)",
                currentUser.tenantId(), keyName, apiKey, agentId, currentUser.userId()
        );
        return ApiResponse.ok(Map.of("keyName", keyName, "apiKey", apiKey));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        jdbc.update("UPDATE api_key SET is_active = false WHERE id = ? AND tenant_id = ?", id, currentUser.tenantId());
        return ApiResponse.ok("API Key disabled");
    }

    /**
     * 通过 API Key 认证，返回关联的 agent_id
     */
    public Map<String, Object> authenticate(String apiKey) {
        List<Map<String, Object>> results = jdbc.queryForList(
                "SELECT id, tenant_id, agent_id, user_id, rate_limit, is_active FROM api_key " +
                        "WHERE api_key = ? AND is_active = true",
                apiKey
        );
        if (results.isEmpty()) return null;

        Map<String, Object> key = results.get(0);
        // 更新最后使用时间
        jdbc.update("UPDATE api_key SET last_used_at = NOW() WHERE id = ?", key.get("id"));
        return key;
    }
}
