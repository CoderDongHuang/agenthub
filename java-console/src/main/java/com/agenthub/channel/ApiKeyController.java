package com.agenthub.channel;

import com.agenthub.common.response.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {

    private final JdbcTemplate jdbc;

    public ApiKeyController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> keys = jdbc.queryForList(
                "SELECT id, key_name, api_key, agent_id, rate_limit, is_active, last_used_at, created_at FROM api_key ORDER BY created_at DESC"
        );
        return ApiResponse.ok(keys);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String keyName = body.getOrDefault("keyName", "API Key");
        String apiKey = "ak-" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Long agentId = body.containsKey("agentId") ? Long.valueOf(body.get("agentId")) : null;

        jdbc.update(
                "INSERT INTO api_key (key_name, api_key, agent_id, user_id, rate_limit) VALUES (?,?,?,1,1000)",
                keyName, apiKey, agentId
        );
        return ApiResponse.ok(Map.of("keyName", keyName, "apiKey", apiKey));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        jdbc.update("UPDATE api_key SET is_active = false WHERE id = ?", id);
        return ApiResponse.ok("API Key disabled");
    }

    /**
     * 通过 API Key 认证，返回关联的 agent_id
     */
    public Map<String, Object> authenticate(String apiKey) {
        List<Map<String, Object>> results = jdbc.queryForList(
                "SELECT id, agent_id, user_id, rate_limit, is_active FROM api_key WHERE api_key = ? AND is_active = true",
                apiKey
        );
        if (results.isEmpty()) return null;

        Map<String, Object> key = results.get(0);
        // 更新最后使用时间
        jdbc.update("UPDATE api_key SET last_used_at = NOW() WHERE id = ?", key.get("id"));
        return key;
    }
}
