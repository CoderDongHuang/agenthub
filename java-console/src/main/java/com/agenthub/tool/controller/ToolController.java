package com.agenthub.tool.controller;

import com.agenthub.common.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private String toJsonString(Object obj) {
        try {
            if (obj instanceof String) return (String) obj;
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, Object> body) {
        String toolCode = (String) body.getOrDefault("toolCode", "");
        String jsonSchema = toJsonString(body.getOrDefault("jsonSchema", "{}"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tool_definition WHERE tool_code = ?", Integer.class, toolCode
        );
        if (count != null && count > 0) {
            jdbc.update(
                    "UPDATE tool_definition SET tool_name=?, description=?, category=?, risk_level=?, rate_limit=?, timeout_seconds=?, json_schema=?::jsonb, updated_at=NOW() WHERE tool_code=?",
                    body.getOrDefault("toolName", ""),
                    body.getOrDefault("description", ""),
                    body.getOrDefault("category", "General"),
                    body.getOrDefault("riskLevel", "low"),
                    body.getOrDefault("rateLimit", 0),
                    body.getOrDefault("timeoutSeconds", 30),
                    jsonSchema,
                    toolCode
            );
        } else {
            jdbc.update(
                    "INSERT INTO tool_definition (tool_name, tool_code, description, category, risk_level, rate_limit, timeout_seconds, json_schema, status) VALUES (?,?,?,?,?,?,?,?::jsonb,'active')",
                    body.getOrDefault("toolName", ""),
                    toolCode,
                    body.getOrDefault("description", ""),
                    body.getOrDefault("category", "General"),
                    body.getOrDefault("riskLevel", "low"),
                    body.getOrDefault("rateLimit", 0),
                    body.getOrDefault("timeoutSeconds", 30),
                    jsonSchema
            );
        }
        return ApiResponse.ok(Map.of("toolCode", toolCode, "status", "registered"));
    }

    @GetMapping
    public ApiResponse<Page<Map<String, Object>>> list(Pageable pageable) {
        List<Map<String, Object>> tools = jdbc.queryForList(
                "SELECT * FROM tool_definition ORDER BY created_at DESC"
        );
        long total = tools.size();
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), tools.size());
        if (start >= tools.size()) start = tools.size();

        return ApiResponse.ok(new PageImpl<>(tools.subList(start, end), pageable, total));
    }
}
