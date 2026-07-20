package com.agenthub.user.controller;

import com.agenthub.common.response.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final JdbcTemplate jdbc;

    public TenantController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "New Tenant");
        jdbc.update("INSERT INTO sys_department (name, tenant_id) VALUES (?, ?)", name, 999);
        // 简单实现: 用 department 的 tenant_id 区分租户
        // 正式版本应有独立的 tenant 表
        return ApiResponse.ok(Map.of("name", name, "status", "created"));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        List<Map<String, Object>> tenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id, name FROM sys_department ORDER BY tenant_id"
        );
        return ApiResponse.ok(tenants);
    }
}
