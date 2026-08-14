package com.agenthub.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@Service
public class WorkspaceResourceQueryService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public WorkspaceResourceQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> list(long tenantId, String type, Pageable pageable) {
        return jdbc.queryForList(
                "SELECT id, resource_type, resource_key, name, description, config::text AS config, status, " +
                        "created_at, updated_at FROM workspace_resource WHERE tenant_id = ? AND resource_type = ? " +
                        "ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                tenantId, type, pageable.getPageSize(), pageable.getOffset()
        ).stream().map(this::normalizeResource).toList();
    }

    public Map<String, Object> get(long tenantId, String type, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, resource_type, resource_key, name, description, config::text AS config, status, " +
                        "created_at, updated_at FROM workspace_resource WHERE id = ? AND tenant_id = ? AND resource_type = ?",
                id, tenantId, type
        );
        if (rows.isEmpty()) throw new NoSuchElementException("Resource not found");
        return normalizeResource(rows.get(0));
    }

    public List<Map<String, Object>> listEnabled(long tenantId, String type) {
        return jdbc.queryForList(
                "SELECT id, resource_key, name, description, config::text AS config, status FROM workspace_resource " +
                        "WHERE tenant_id = ? AND resource_type = ? AND COALESCE((config->>'enabled')::boolean, false) = true",
                tenantId, type
        ).stream().map(this::normalizeResource).toList();
    }

    public List<Map<String, Object>> listExecutions(long tenantId, long resourceId, Pageable pageable) {
        return jdbc.queryForList(
                "SELECT id, status, result::text AS result, started_at, completed_at FROM workspace_execution " +
                        "WHERE tenant_id = ? AND resource_id = ? ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?",
                tenantId, resourceId, pageable.getPageSize(), pageable.getOffset()
        ).stream().map(this::normalizeExecution).toList();
    }

    private Map<String, Object> normalizeResource(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>(row);
        normalized.put("config", parseJson(row.get("config")));
        return normalized;
    }

    private Map<String, Object> normalizeExecution(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>(row);
        normalized.put("result", parseJson(row.get("result")));
        normalized.put("executionId", row.get("id"));
        return normalized;
    }

    private Object parseJson(Object value) {
        if (value == null) return Map.of();
        try {
            return objectMapper.readValue(value.toString(), Object.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid stored JSON", exception);
        }
    }
}
