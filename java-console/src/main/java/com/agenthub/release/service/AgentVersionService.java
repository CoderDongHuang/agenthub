package com.agenthub.release.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
public class AgentVersionService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentVersionService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> snapshot(Long tenantId, Long userId, Long agentId, String note) {
        Map<String, Object> agent = requireAgent(tenantId, agentId);
        Integer nextVersion = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0)+1 FROM agent_version WHERE tenant_id=? AND agent_id=?",
                Integer.class, tenantId, agentId);
        Map<String, Object> config = agentConfig(agent);
        Long id = jdbc.queryForObject(
                "INSERT INTO agent_version(tenant_id,agent_id,version_no,config,status,change_note,created_by) " +
                        "VALUES (?,?,?,?::jsonb,'draft',?,?) RETURNING id",
                Long.class, tenantId, agentId, nextVersion, json(config), cleanNote(note), userId);
        return get(tenantId, agentId, id);
    }

    public List<Map<String, Object>> list(Long tenantId, Long agentId) {
        requireAgent(tenantId, agentId);
        return jdbc.queryForList(
                "SELECT id,agent_id,version_no,status,rollout_percent,evaluation_status,change_note," +
                        "config::text AS config,created_by,created_at,published_at " +
                        "FROM agent_version WHERE tenant_id=? AND agent_id=? ORDER BY version_no DESC",
                tenantId, agentId).stream().map(this::normalizeVersion).toList();
    }

    public Map<String, Object> get(Long tenantId, Long agentId, Long versionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,agent_id,version_no,status,rollout_percent,evaluation_status,change_note," +
                        "config::text AS config,created_by,created_at,published_at " +
                        "FROM agent_version WHERE id=? AND tenant_id=? AND agent_id=?",
                versionId, tenantId, agentId);
        if (rows.isEmpty()) throw new NoSuchElementException("Agent version not found");
        return normalizeVersion(rows.get(0));
    }

    public Map<String, Object> diff(Long tenantId, Long agentId, Long leftId, Long rightId) {
        Map<String, Object> left = get(tenantId, agentId, leftId);
        Map<String, Object> right = get(tenantId, agentId, rightId);
        Map<String, Object> leftConfig = asMap(left.get("config"));
        Map<String, Object> rightConfig = asMap(right.get("config"));
        Set<String> keys = new TreeSet<>();
        keys.addAll(leftConfig.keySet());
        keys.addAll(rightConfig.keySet());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (String key : keys) {
            Object before = leftConfig.get(key);
            Object after = rightConfig.get(key);
            if (!Objects.equals(before, after)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("field", key);
                change.put("before", before);
                change.put("after", after);
                changes.add(change);
            }
        }
        return Map.of("left", left, "right", right, "changes", changes, "changedFields", changes.size());
    }

    @Transactional
    public Map<String, Object> release(Long tenantId, Long agentId, Long versionId, int rolloutPercent) {
        if (rolloutPercent < 1 || rolloutPercent > 100) {
            throw new IllegalArgumentException("rolloutPercent must be between 1 and 100");
        }
        Map<String, Object> version = get(tenantId, agentId, versionId);
        enforceEvaluationGate(tenantId, versionId, String.valueOf(version.get("evaluation_status")));
        String status = rolloutPercent == 100 ? "published" : "canary";
        if (rolloutPercent == 100) {
            jdbc.update("UPDATE agent_version SET status='archived',rollout_percent=0 " +
                            "WHERE tenant_id=? AND agent_id=? AND id<>? AND status IN ('published','canary')",
                    tenantId, agentId, versionId);
        } else {
            jdbc.update("UPDATE agent_version SET status='archived',rollout_percent=0 " +
                            "WHERE tenant_id=? AND agent_id=? AND id<>? AND status='canary'",
                    tenantId, agentId, versionId);
        }
        jdbc.update("UPDATE agent_version SET status=?,rollout_percent=?,published_at=NOW() WHERE id=?",
                status, rolloutPercent, versionId);
        if (rolloutPercent == 100) applyVersionToAgent(tenantId, agentId, versionId);
        return get(tenantId, agentId, versionId);
    }

    @Transactional
    public Map<String, Object> rollback(Long tenantId, Long agentId, Long versionId) {
        get(tenantId, agentId, versionId);
        jdbc.update("UPDATE agent_version SET status='archived',rollout_percent=0 " +
                        "WHERE tenant_id=? AND agent_id=? AND status IN ('published','canary')",
                tenantId, agentId);
        jdbc.update("UPDATE agent_version SET status='published',rollout_percent=100,published_at=NOW() WHERE id=?",
                versionId);
        applyVersionToAgent(tenantId, agentId, versionId);
        return get(tenantId, agentId, versionId);
    }

    public ResolvedVersion resolve(Long tenantId, Long agentId, String stableKey) {
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT id,version_no,status,rollout_percent,config::text AS config FROM agent_version " +
                        "WHERE tenant_id=? AND agent_id=? AND status IN ('canary','published') " +
                        "ORDER BY CASE status WHEN 'canary' THEN 0 ELSE 1 END, version_no DESC",
                tenantId, agentId);
        Map<String, Object> selected = null;
        for (Map<String, Object> candidate : candidates) {
            if ("canary".equals(candidate.get("status"))) {
                int bucket = Math.floorMod(Objects.requireNonNullElse(stableKey, "").hashCode(), 100);
                if (bucket < ((Number) candidate.get("rollout_percent")).intValue()) {
                    selected = candidate;
                    break;
                }
            } else if (selected == null) {
                selected = candidate;
            }
        }
        if (selected == null) {
            Map<String, Object> agent = requireAgent(tenantId, agentId);
            return new ResolvedVersion(null, 0, agentConfig(agent), "live_definition");
        }
        return new ResolvedVersion(((Number) selected.get("id")).longValue(),
                ((Number) selected.get("version_no")).intValue(), parseMap(selected.get("config")),
                String.valueOf(selected.get("status")));
    }

    public void markEvaluation(Long tenantId, Long versionId, boolean passed) {
        jdbc.update("UPDATE agent_version SET evaluation_status=? WHERE id=? AND tenant_id=?",
                passed ? "passed" : "failed", versionId, tenantId);
    }

    @Transactional
    public Map<String, Object> guardCanary(Long tenantId, Long agentId, Long candidateVersionId,
                                           Long baselineVersionId, BigDecimal minimumScoreDelta) {
        get(tenantId, agentId, candidateVersionId);
        get(tenantId, agentId, baselineVersionId);
        BigDecimal baseline = latestEvaluationScore(tenantId, baselineVersionId);
        BigDecimal candidate = latestEvaluationScore(tenantId, candidateVersionId);
        BigDecimal delta = candidate.subtract(baseline);
        boolean rollback = candidate.compareTo(BigDecimal.ZERO) == 0 || delta.compareTo(minimumScoreDelta) < 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateVersionId", candidateVersionId);
        result.put("baselineVersionId", baselineVersionId);
        result.put("baselineScore", baseline);
        result.put("candidateScore", candidate);
        result.put("scoreDelta", delta);
        result.put("rollbackTriggered", rollback);
        if (rollback) {
            result.put("version", rollback(tenantId, agentId, baselineVersionId));
            result.put("reason", "Candidate score fell below the canary guard threshold");
        } else {
            result.put("reason", "Candidate remains within the canary guard threshold");
        }
        return result;
    }

    private BigDecimal latestEvaluationScore(Long tenantId, Long versionId) {
        List<BigDecimal> scores = jdbc.query("SELECT score FROM evaluation_run WHERE tenant_id=? AND agent_version_id=? " +
                        "ORDER BY completed_at DESC NULLS LAST, id DESC LIMIT 1",
                (rs, rowNum) -> rs.getBigDecimal(1), tenantId, versionId);
        if (scores.isEmpty()) throw new NoSuchElementException("No evaluation run for version " + versionId);
        return Objects.requireNonNullElse(scores.get(0), BigDecimal.ZERO);
    }

    private void enforceEvaluationGate(Long tenantId, Long versionId, String evaluationStatus) {
        Integer activeDatasets = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_dataset WHERE tenant_id=? AND status='active'",
                Integer.class, tenantId);
        if (activeDatasets != null && activeDatasets > 0 && !"passed".equals(evaluationStatus)) {
            throw new IllegalStateException("Release blocked: active evaluation datasets must pass first");
        }
        if (activeDatasets != null && activeDatasets > 0) {
            Integer passedRuns = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM evaluation_run WHERE tenant_id=? AND agent_version_id=? AND status='passed'",
                    Integer.class, tenantId, versionId);
            if (passedRuns == null || passedRuns == 0) {
                throw new IllegalStateException("Release blocked: no passing evaluation run for this version");
            }
        }
    }

    private void applyVersionToAgent(Long tenantId, Long agentId, Long versionId) {
        Map<String, Object> config = parseMap(jdbc.queryForObject(
                "SELECT config::text FROM agent_version WHERE id=?", String.class, versionId));
        jdbc.update("UPDATE agent_definition SET name=?,description=?,system_prompt=?,model=?,temperature=?," +
                        "max_tokens=?,icon=?,status='published',published_at=NOW(),current_version_id=?,updated_at=NOW() " +
                        "WHERE id=? AND tenant_id=?",
                text(config.get("name")), text(config.get("description")), text(config.get("systemPrompt")),
                text(config.get("model")), decimal(config.get("temperature"), BigDecimal.valueOf(0.7)),
                integer(config.get("maxTokens"), 4096), text(config.get("icon")), versionId, agentId, tenantId);
    }

    private Map<String, Object> requireAgent(Long tenantId, Long agentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,name,description,system_prompt,model,temperature,max_tokens,icon,status " +
                        "FROM agent_definition WHERE id=? AND tenant_id=?", agentId, tenantId);
        if (rows.isEmpty()) throw new NoSuchElementException("Agent not found");
        return rows.get(0);
    }

    private Map<String, Object> agentConfig(Map<String, Object> row) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("name", row.get("name"));
        config.put("description", Objects.requireNonNullElse(row.get("description"), ""));
        config.put("systemPrompt", row.get("system_prompt"));
        config.put("model", row.get("model"));
        config.put("temperature", row.get("temperature"));
        config.put("maxTokens", row.get("max_tokens"));
        config.put("icon", Objects.requireNonNullElse(row.get("icon"), ""));
        return config;
    }

    private Map<String, Object> normalizeVersion(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("config", parseMap(row.get("config")));
        return result;
    }

    private Map<String, Object> parseMap(Object value) {
        if (value instanceof Map<?, ?>) return asMap(value);
        try {
            return mapper.readValue(String.valueOf(value), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid version configuration", exception);
        }
    }

    private Map<String, Object> asMap(Object value) {
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize version", exception);
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String cleanNote(String note) {
        return note == null || note.isBlank() ? "Manual snapshot" : note.trim();
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value instanceof BigDecimal decimal) return decimal;
        try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    public record ResolvedVersion(Long id, int version, Map<String, Object> config, String channel) {}
}
