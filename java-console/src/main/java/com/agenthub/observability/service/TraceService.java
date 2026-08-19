package com.agenthub.observability.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class TraceService {

    private static final Pattern EMAIL = Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SECRET = Pattern.compile("(?i)(sk-[a-z0-9._-]{8,}|bearer\\s+[a-z0-9._-]{8,}|(?:token|secret|password|api[_-]?key)\\s*[:=]\\s*[^,;\\s]+)");

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
                        "output_tokens,total_cost,latency_ms,first_token_latency_ms,retry_count,cancellation_count," +
                        "queue_depth,provider_error,started_at,completed_at FROM execution_trace " +
                        "WHERE tenant_id=? ORDER BY started_at DESC LIMIT ?", tenantId, safeLimit);
    }

    public Map<String, Object> get(Long tenantId, UUID traceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT trace_id,session_id,agent_id,agent_version_id,model,route_reason,status,input_tokens," +
                        "output_tokens,total_cost,latency_ms,first_token_latency_ms,retry_count,cancellation_count," +
                        "queue_depth,provider_error,started_at,completed_at FROM execution_trace " +
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

    public Map<String, Object> observability(Long tenantId, int requestedHours) {
        int hours = Math.max(1, Math.min(requestedHours, 168));
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) AS traces,
                       COUNT(*) FILTER (WHERE status='success') AS succeeded,
                       COUNT(*) FILTER (WHERE status='failed') AS failed,
                       COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms),0)::BIGINT AS latency_p50_ms,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms),0)::BIGINT AS latency_p95_ms,
                       COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY first_token_latency_ms),0)::BIGINT AS first_token_p50_ms,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY first_token_latency_ms),0)::BIGINT AS first_token_p95_ms,
                       COALESCE(SUM(CASE WHEN provider_error THEN 1 ELSE 0 END),0) AS provider_errors,
                       COALESCE(SUM(retry_count),0) AS retries,
                       COALESCE(SUM(cancellation_count),0) AS cancellations,
                       COALESCE(AVG(queue_depth),0)::BIGINT AS queue_depth,
                       COALESCE(SUM(input_tokens+output_tokens),0) AS tokens,
                       COALESCE(SUM(total_cost),0) AS cost
                FROM execution_trace
                WHERE tenant_id=? AND started_at >= NOW() - (? * INTERVAL '1 hour')
                """, tenantId, hours);
        Map<String, Object> tool = jdbc.queryForMap("""
                SELECT COUNT(*) AS calls,
                       COUNT(*) FILTER (WHERE span.status IN ('completed','success')) AS succeeded,
                       COALESCE(AVG(span.duration_ms),0)::BIGINT AS avg_duration_ms
                FROM execution_span span JOIN execution_trace trace ON trace.trace_id=span.trace_id
                WHERE trace.tenant_id=? AND trace.started_at >= NOW() - (? * INTERVAL '1 hour')
                  AND span.span_type IN ('tool','retrieval')
                """, tenantId, hours);
        List<Map<String, Object>> attribution = jdbc.queryForList("""
                SELECT COALESCE(trace.model,'unknown') AS model, trace.agent_id,
                       COUNT(*) AS traces, COALESCE(SUM(trace.total_cost),0) AS cost,
                       COALESCE(AVG(trace.latency_ms),0)::BIGINT AS avg_latency_ms,
                       COUNT(*) FILTER (WHERE trace.status='success') AS successful_traces
                FROM execution_trace trace
                WHERE trace.tenant_id=? AND trace.started_at >= NOW() - (? * INTERVAL '1 hour')
                GROUP BY trace.model, trace.agent_id ORDER BY cost DESC, traces DESC
                """, tenantId, hours);
        List<Map<String, Object>> spanAttribution = jdbc.queryForList("""
                SELECT span.span_type AS dimension, COALESCE(span.name,'unknown') AS name,
                       COUNT(*) AS calls, COALESCE(SUM(span.duration_ms),0) AS duration_ms,
                       COUNT(*) FILTER (WHERE span.status IN ('completed','success')) AS successful_calls
                FROM execution_span span JOIN execution_trace trace ON trace.trace_id=span.trace_id
                WHERE trace.tenant_id=? AND trace.started_at >= NOW() - (? * INTERVAL '1 hour')
                  AND span.span_type IN ('tool','retrieval')
                GROUP BY span.span_type, span.name ORDER BY calls DESC
                """, tenantId, hours);
        long traces = number(totals.get("traces"));
        double errorRate = traces == 0 ? 0 : number(totals.get("failed")) * 100.0 / traces;
        double providerErrorRate = traces == 0 ? 0 : number(totals.get("provider_errors")) * 100.0 / traces;
        double toolSuccessRate = number(tool.get("calls")) == 0 ? 100 :
                number(tool.get("succeeded")) * 100.0 / number(tool.get("calls"));
        List<Map<String, Object>> anomalies = new ArrayList<>();
        if (errorRate >= 5) anomalies.add(Map.of("type", "error_rate", "severity", "warning", "value", errorRate));
        if (providerErrorRate >= 5) anomalies.add(Map.of("type", "provider_error_rate", "severity", "critical", "value", providerErrorRate));
        if (number(totals.get("latency_p95_ms")) > 10000) anomalies.add(Map.of("type", "latency_p95", "severity", "warning", "value", totals.get("latency_p95_ms")));
        return Map.of("windowHours", hours, "totals", totals, "tool", tool,
                "errorRatePercent", round(errorRate), "providerErrorRatePercent", round(providerErrorRate),
                "toolSuccessRatePercent", round(toolSuccessRate), "attribution", attribution,
                "spanAttribution", spanAttribution, "anomalies", anomalies);
    }

    public Map<String, Object> replay(Long tenantId, UUID traceId) {
        Map<String, Object> trace = get(tenantId, traceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("mode", "redacted_replay");
        result.put("replayable", true);
        result.put("notice", "Replay is a redacted, side-effect-free reconstruction; tool execution is not repeated automatically.");
        result.put("trace", redact(trace));
        return result;
    }

    public Map<String, Object> addFeedback(Long tenantId, Long userId, UUID traceId, int rating, String comment) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("rating must be between 1 and 5");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM execution_trace WHERE tenant_id=? AND trace_id=?", Integer.class, tenantId, traceId) == 0) {
            throw new NoSuchElementException("Trace not found");
        }
        jdbc.update("""
                INSERT INTO trace_feedback(tenant_id,trace_id,user_id,rating,comment)
                VALUES (?,?,?,?,?) ON CONFLICT (tenant_id,trace_id,user_id)
                DO UPDATE SET rating=EXCLUDED.rating,comment=EXCLUDED.comment,created_at=NOW()
                """, tenantId, traceId, userId, rating, truncate(comment));
        return jdbc.queryForMap("SELECT id,trace_id,rating,comment,created_at FROM trace_feedback WHERE tenant_id=? AND trace_id=? AND user_id=?",
                tenantId, traceId, userId);
    }

    public List<Map<String, Object>> feedback(Long tenantId, UUID traceId) {
        return jdbc.queryForList("SELECT id,trace_id,user_id,rating,comment,created_at FROM trace_feedback WHERE tenant_id=? AND trace_id=? ORDER BY created_at DESC", tenantId, traceId);
    }

    public void recordOperational(UUID traceId, long firstTokenLatencyMs, int retries, int cancellations, int queueDepth, boolean providerError) {
        jdbc.update("UPDATE execution_trace SET first_token_latency_ms=?,retry_count=?,cancellation_count=?,queue_depth=?,provider_error=? WHERE trace_id=?",
                Math.max(0, firstTokenLatencyMs), Math.max(0, retries), Math.max(0, cancellations), Math.max(0, queueDepth), providerError, traceId);
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

    private Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                result.put(name, name.matches("(?i).*?(token|secret|password|api[_-]?key).*?") ? "[REDACTED]" : redact(item));
            });
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(this::redact).toList();
        if (value instanceof String text) {
            String normalized = EMAIL.matcher(text).replaceAll("[EMAIL]");
            normalized = PHONE.matcher(normalized).replaceAll("[PHONE]");
            return SECRET.matcher(normalized).replaceAll("[REDACTED]");
        }
        return value;
    }

    private long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }

    public long elapsedMillis(Instant started) {
        return Math.max(0, Duration.between(started, Instant.now()).toMillis());
    }
}
