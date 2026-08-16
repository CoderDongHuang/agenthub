package com.agenthub.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class WorkflowAutomationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkflowAutomationService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> templates(Long tenantId) {
        return jdbc.queryForList(
                "SELECT id,template_key,name,description,config::text AS config,version,created_at,updated_at " +
                        "FROM workflow_template WHERE tenant_id=? ORDER BY updated_at DESC", tenantId).stream()
                .map(this::normalizeTemplate).toList();
    }

    public Map<String, Object> createTemplate(Long tenantId, Long userId, Map<String, Object> body) {
        String key = text(body.get("templateKey"));
        if (key.isBlank()) key = "template-" + UUID.randomUUID();
        String name = required(body, "name");
        Map<String, Object> config = asMap(body.getOrDefault("config", Map.of("nodes", List.of())));
        int version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM workflow_template WHERE tenant_id=? AND template_key=?",
                Integer.class, tenantId, key);
        Long id = jdbc.queryForObject(
                "INSERT INTO workflow_template(tenant_id,template_key,name,description,config,version,created_by) " +
                        "VALUES (?,?,?,?,?::jsonb,?,?) RETURNING id", Long.class, tenantId, key, name,
                text(body.get("description")), json(config), version, userId);
        return getTemplate(tenantId, id);
    }

    @Transactional
    public Map<String, Object> instantiate(Long tenantId, Long userId, Long templateId, Map<String, Object> body) {
        Map<String, Object> template = getTemplate(tenantId, templateId);
        String name = text(body.get("name"));
        if (name.isBlank()) name = template.get("name") + " Copy";
        Long id = jdbc.queryForObject(
                "INSERT INTO workspace_resource(tenant_id,resource_type,resource_key,name,description,config,status,created_by) " +
                        "VALUES (?,'workflow',?,?,?,?::jsonb,'draft',?) RETURNING id", Long.class, tenantId,
                "workflow-" + UUID.randomUUID(), name, template.get("description"),
                json(template.get("config")), userId);
        return jdbc.queryForMap("SELECT id,resource_key,name,description,config::text AS config,status,created_at,updated_at " +
                "FROM workspace_resource WHERE id=?", id);
    }

    @Transactional
    public Map<String, Object> attachSubflow(Long tenantId, Long workflowId, Long templateId, Map<String, Object> body) {
        Map<String, Object> workflow = requireWorkflow(tenantId, workflowId);
        Map<String, Object> template = getTemplate(tenantId, templateId);
        Map<String, Object> config = parseMap(workflow.get("config"));
        List<Map<String, Object>> nodes = mutableNodes(config.get("nodes"));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", text(body.get("nodeId")).isBlank() ? "subflow-" + UUID.randomUUID() : text(body.get("nodeId")));
        node.put("type", "subflow");
        node.put("title", text(body.get("title")).isBlank() ? template.get("name") : text(body.get("title")));
        node.put("detail", "Reusable workflow template v" + template.get("version"));
        node.put("templateId", templateId);
        node.put("templateVersion", template.get("version"));
        node.put("inputMapping", body.getOrDefault("inputMapping", Map.of()));
        node.put("x", intValue(body.get("x"), 250));
        node.put("y", intValue(body.get("y"), 250));
        nodes.add(node);
        config.put("nodes", nodes);
        jdbc.update("UPDATE workspace_resource SET config=?::jsonb,updated_at=NOW() WHERE id=? AND tenant_id=?",
                json(config), workflowId, tenantId);
        return Map.of("workflowId", workflowId, "subflow", node, "nodeCount", nodes.size());
    }

    public List<Map<String, Object>> triggers(Long tenantId) {
        return jdbc.queryForList(
                "SELECT trigger.id,trigger.workflow_id,resource.name AS workflow_name,trigger.trigger_type," +
                        "trigger.trigger_key,trigger.cron_expression,trigger.interval_seconds,trigger.payload::text AS payload," +
                        "trigger.enabled,trigger.next_run_at,trigger.last_run_at,trigger.created_at,trigger.updated_at " +
                        "FROM workflow_trigger trigger JOIN workspace_resource resource ON resource.id=trigger.workflow_id " +
                        "WHERE trigger.tenant_id=? ORDER BY trigger.updated_at DESC", tenantId).stream()
                .map(this::normalizeTrigger).toList();
    }

    public Map<String, Object> createTrigger(Long tenantId, Long userId, Map<String, Object> body) {
        Long workflowId = longValue(body.get("workflowId"));
        requireWorkflow(tenantId, workflowId);
        String type = required(body, "triggerType");
        if (!Set.of("schedule", "webhook").contains(type)) throw new IllegalArgumentException("Unsupported triggerType");
        String key = text(body.get("triggerKey"));
        if (key.isBlank()) key = type + "-" + UUID.randomUUID();
        String secret = type.equals("webhook") ? UUID.randomUUID() + "." + UUID.randomUUID() : null;
        int interval = intValue(body.get("intervalSeconds"), 3600);
        if (type.equals("schedule") && interval < 30) throw new IllegalArgumentException("intervalSeconds must be at least 30");
        Long id = jdbc.queryForObject(
                "INSERT INTO workflow_trigger(tenant_id,workflow_id,trigger_type,trigger_key,secret_hash,cron_expression," +
                        "interval_seconds,payload,next_run_at,created_by) VALUES (?,?,?,?,?,?,?,?::jsonb,?::timestamp,?) RETURNING id",
                Long.class, tenantId, workflowId, type, key, secret == null ? null : sha256(secret),
                text(body.get("cronExpression")), type.equals("schedule") ? interval : null,
                json(body.getOrDefault("payload", Map.of())),
                type.equals("schedule") ? LocalDateTime.now().plusSeconds(interval).toString() : null, userId);
        Map<String, Object> result = new LinkedHashMap<>(getTrigger(tenantId, id));
        if (secret != null) result.put("secret", secret);
        result.put("secretReturnedOnce", secret != null);
        return result;
    }

    @Transactional
    public Map<String, Object> fireWebhook(String triggerKey, String secret, Map<String, Object> payload) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM workflow_trigger WHERE trigger_key=? AND trigger_type='webhook' AND enabled=TRUE",
                triggerKey);
        if (rows.isEmpty()) throw new NoSuchElementException("Webhook trigger not found");
        Map<String, Object> trigger = rows.get(0);
        if (!MessageDigest.isEqual(sha256(secret).getBytes(StandardCharsets.UTF_8),
                text(trigger.get("secret_hash")).getBytes(StandardCharsets.UTF_8))) {
            throw new SecurityException("Invalid webhook trigger secret");
        }
        Map<String, Object> merged = new LinkedHashMap<>(parseMap(trigger.get("payload")));
        if (payload != null) merged.putAll(payload);
        return enqueue(trigger, merged, "webhook");
    }

    @Scheduled(fixedDelayString = "${agenthub.workflow.scheduler-interval-ms:15000}", initialDelay = 15000)
    @Transactional
    public void enqueueDueSchedules() {
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT * FROM workflow_trigger WHERE trigger_type='schedule' AND enabled=TRUE " +
                        "AND next_run_at<=NOW() FOR UPDATE SKIP LOCKED");
        for (Map<String, Object> trigger : due) {
            enqueue(trigger, parseMap(trigger.get("payload")), "schedule");
            int interval = ((Number) trigger.get("interval_seconds")).intValue();
            jdbc.update("UPDATE workflow_trigger SET last_run_at=NOW(),next_run_at=NOW()+(?*INTERVAL '1 second')," +
                    "updated_at=NOW() WHERE id=?", interval, trigger.get("id"));
        }
    }

    private Map<String, Object> enqueue(Map<String, Object> trigger, Map<String, Object> payload, String source) {
        Long workflowId = ((Number) trigger.get("workflow_id")).longValue();
        Long tenantId = ((Number) trigger.get("tenant_id")).longValue();
        Long executionId = jdbc.queryForObject(
                "INSERT INTO workspace_execution(resource_id,tenant_id,execution_type,status,result,input,current_step," +
                        "idempotency_key,created_by) VALUES (?,?,'workflow_run','queued','{}'::jsonb,?::jsonb,0,?,NULL) RETURNING id",
                Long.class, workflowId, tenantId, json(payload), source + ":" + trigger.get("id") + ":" + UUID.randomUUID());
        Map<String, Object> event = Map.of("source", source, "triggerId", trigger.get("id"), "payload", payload);
        jdbc.update("INSERT INTO workflow_execution_event(execution_id,event_type,payload) VALUES (?,'trigger_received',?::jsonb)",
                executionId, json(event));
        jdbc.update("UPDATE workflow_trigger SET last_run_at=NOW(),updated_at=NOW() WHERE id=?", trigger.get("id"));
        return Map.of("executionId", executionId, "workflowId", workflowId, "status", "queued", "source", source);
    }

    private Map<String, Object> getTemplate(Long tenantId, Long templateId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,template_key,name,description,config::text AS config,version,created_at,updated_at " +
                        "FROM workflow_template WHERE tenant_id=? AND id=?", tenantId, templateId);
        if (rows.isEmpty()) throw new NoSuchElementException("Workflow template not found");
        return normalizeTemplate(rows.get(0));
    }

    private Map<String, Object> normalizeTemplate(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("config", parseMap(row.get("config")));
        return result;
    }

    private Map<String, Object> getTrigger(Long tenantId, Long triggerId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,workflow_id,trigger_type,trigger_key,cron_expression,interval_seconds,payload::text AS payload," +
                        "enabled,next_run_at,last_run_at,created_at,updated_at FROM workflow_trigger WHERE tenant_id=? AND id=?",
                tenantId, triggerId);
        if (rows.isEmpty()) throw new NoSuchElementException("Workflow trigger not found");
        return normalizeTrigger(rows.get(0));
    }

    private Map<String, Object> normalizeTrigger(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("payload", parseMap(row.get("payload")));
        return result;
    }

    private Map<String, Object> requireWorkflow(Long tenantId, Long workflowId) {
        if (workflowId == null) throw new IllegalArgumentException("workflowId is required");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id,name,description,config::text AS config,status FROM workspace_resource " +
                        "WHERE id=? AND tenant_id=? AND resource_type='workflow'", workflowId, tenantId);
        if (rows.isEmpty()) throw new NoSuchElementException("Workflow not found");
        return rows.get(0);
    }

    private List<Map<String, Object>> mutableNodes(Object value) {
        if (!(value instanceof List<?> list)) return new ArrayList<>();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) result.add(new LinkedHashMap<>(asMap(item)));
        return result;
    }

    private String required(Map<String, Object> body, String key) {
        String value = text(body.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }
    private Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.valueOf(String.valueOf(value)); } catch (Exception exception) { return null; }
    }
    private Map<String, Object> asMap(Object value) {
        if (value == null) return Map.of();
        return mapper.convertValue(value, new TypeReference<>() {});
    }
    private Map<String, Object> parseMap(Object value) {
        if (value instanceof Map<?, ?>) return asMap(value);
        try { return mapper.readValue(String.valueOf(value), new TypeReference<>() {}); }
        catch (Exception exception) { return new LinkedHashMap<>(); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception exception) { throw new IllegalArgumentException("Unable to serialize workflow data", exception); }
    }
    private String sha256(String value) {
        if (value == null) value = "";
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
}
