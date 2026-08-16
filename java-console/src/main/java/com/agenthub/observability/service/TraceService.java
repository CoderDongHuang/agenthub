package com.agenthub.observability.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class TraceService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TraceService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID start(Long tenantId, String sessionId, Long agentId, Long versionId, String model,
                      String routeReason, String userMessage) {
        UUID traceId = UUID.randomUUID();
        jdbc.update("INSERT INTO execution_trace(trace_id,tenant_id,session_id,agent_id,agent_version_id,model,route_reason) " +
                        "VALUES (?,?,?,?,?,?,?)", traceId, tenantId, sessionId, agentId, versionId, model, routeReason);
        addSpan(traceId, "input", "User message", "completed", Map.of("message", truncate(userMessage)),
                Map.of(), Map.of("characters", userMessage == null ? 0 : userMessage.length()), 0);
        return traceId;
    }

    public void addSpan(UUID traceId, String type, String name, String status, Object input, Object output,
                        Object metadata, long durationMs) {
        jdbc.update("INSERT INTO execution_span(trace_id,span_type,name,status,input,output,metadata,completed_at,duration_ms) " +
                        "VALUES (?,?,?,?,?::jsonb,?::jsonb,?::jsonb,NOW(),?)",
                traceId, type, name, status, json(input), json(output), json(metadata), Math.max(0, durationMs));
    }

    public void complete(UUID traceId, String status, int inputTokens, int outputTokens, BigDecimal cost,
                         long latencyMs, String output) {
        addSpan(traceId, "model", "Model response", status,
                Map.of(), Map.of("content", truncate(output)),
                Map.of("inputTokens", inputTokens, "outputTokens", outputTokens), latencyMs);
        jdbc.update("UPDATE execution_trace SET status=?,input_tokens=?,output_tokens=?,total_cost=?,latency_ms=?," +
                        "completed_at=NOW() WHERE trace_id=?", status, inputTokens, outputTokens,
                cost == null ? BigDecimal.ZERO : cost, Math.max(0, latencyMs), traceId);
    }

    public List<Map<String, Object>> list(Long tenantId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList(
                "SELECT trace_id,session_id,agent_id,agent_version_id,model,route_reason,status,input_tokens," +
                        "output_tokens,total_cost,latency_ms,started_at,completed_at FROM execution_trace " +
                        "WHERE tenant_id=? ORDER BY started_at DESC LIMIT ?", tenantId, safeLimit);
    }

    public Map<String, Object> get(Long tenantId, UUID traceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT trace_id,session_id,agent_id,agent_version_id,model,route_reason,status,input_tokens," +
                        "output_tokens,total_cost,latency_ms,started_at,completed_at FROM execution_trace " +
                        "WHERE tenant_id=? AND trace_id=?", tenantId, traceId);
        if (rows.isEmpty()) throw new NoSuchElementException("Trace not found");
        Map<String, Object> result = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> spans = jdbc.queryForList(
                "SELECT id,span_type,name,status,input::text AS input,output::text AS output," +
                        "metadata::text AS metadata,started_at,completed_at,duration_ms FROM execution_span " +
                        "WHERE trace_id=? ORDER BY id", traceId).stream().map(this::normalizeSpan).toList();
        result.put("spans", spans);
        return result;
    }

    public Map<String, Object> summary(Long tenantId) {
        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT COUNT(*) AS traces,COUNT(*) FILTER (WHERE status='success') AS succeeded," +
                        "COUNT(*) FILTER (WHERE status='failed') AS failed,COALESCE(AVG(latency_ms),0)::BIGINT AS avg_latency_ms," +
                        "COALESCE(SUM(input_tokens+output_tokens),0) AS tokens,COALESCE(SUM(total_cost),0) AS cost " +
                        "FROM execution_trace WHERE tenant_id=? AND started_at>=NOW()-INTERVAL '24 hours'", tenantId);
        List<Map<String, Object>> byType = jdbc.queryForList(
                "SELECT span_type,COUNT(*) AS count,COALESCE(AVG(duration_ms),0)::BIGINT AS avg_duration_ms " +
                        "FROM execution_span span JOIN execution_trace trace ON trace.trace_id=span.trace_id " +
                        "WHERE trace.tenant_id=? AND trace.started_at>=NOW()-INTERVAL '24 hours' GROUP BY span_type ORDER BY count DESC",
                tenantId);
        return Map.of("window", "24h", "totals", totals, "spanTypes", byType);
    }

    private Map<String, Object> normalizeSpan(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("input", parse(row.get("input")));
        result.put("output", parse(row.get("output")));
        result.put("metadata", parse(row.get("metadata")));
        return result;
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 8_000 ? value : value.substring(0, 8_000) + "...";
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalArgumentException("Unable to serialize trace data", exception); }
    }

    private Object parse(Object value) {
        if (!(value instanceof String text)) return value;
        try { return mapper.readValue(text, new TypeReference<>() {}); }
        catch (Exception exception) { return Map.of("raw", text); }
    }

    public long elapsedMillis(Instant started) {
        return Math.max(0, Duration.between(started, Instant.now()).toMillis());
    }
}
