package com.agenthub.workflow.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agenthub.grpc.PythonAgentClient;
import com.agenthub.grpc.stub.ExecutionRequest;
import com.agenthub.grpc.stub.ExecutionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class WorkflowAutomationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PythonAgentClient runtime;
    private final String runtimeUrl;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String internalToken;

    public WorkflowAutomationService(JdbcTemplate jdbc, ObjectMapper mapper, PythonAgentClient runtime,
                                     @Value("${python.runtime.base-url:http://localhost:8000}") String runtimeUrl,
                                     @Value("${agenthub.internal-token}") String internalToken) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.runtime = runtime;
        this.runtimeUrl = runtimeUrl.replaceAll("/+$", "");
        this.internalToken = internalToken;
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

    @Transactional
    public Map<String, Object> enqueueManual(Long tenantId, Long userId, Long workflowId,
                                              Map<String, Object> input, String idempotencyKey) {
        Map<String, Object> workflow = requireWorkflow(tenantId, workflowId);
        List<Map<String, Object>> nodes = mutableNodes(parseMap(workflow.get("config")).get("nodes"));
        if (nodes.isEmpty()) throw new IllegalArgumentException("Workflow has no executable nodes");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT id,status,result::text AS result,started_at FROM workspace_execution " +
                            "WHERE tenant_id=? AND resource_id=? AND idempotency_key=?",
                    tenantId, workflowId, idempotencyKey);
            if (!existing.isEmpty()) return normalizeExecution(existing.getFirst());
        }
        Long executionId = jdbc.queryForObject(
                "INSERT INTO workspace_execution(resource_id,tenant_id,execution_type,status,result,input,current_step," +
                        "idempotency_key,created_by) VALUES (?,?,'workflow_run','queued',?::jsonb,?::jsonb,0,?,?) RETURNING id",
                Long.class, workflowId, tenantId,
                json(Map.of("workflowId", workflowId, "workflowName", workflow.get("name"), "steps", List.of(), "status", "queued")),
                json(input), idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey, userId);
        jdbc.update("INSERT INTO workflow_execution_event(execution_id,event_type,payload) VALUES (?,'queued',?::jsonb)",
                executionId, json(Map.of("source", "manual", "input", input)));
        return Map.of("executionId", executionId, "workflowId", workflowId, "status", "queued");
    }

    @Scheduled(fixedDelayString = "${agenthub.workflow.worker-interval-ms:2000}", initialDelay = 3000)
    public void processQueuedExecutions() {
        String workerId = UUID.randomUUID().toString();
        jdbc.update("UPDATE workspace_execution SET status='retrying',worker_id=NULL,lease_expires_at=NULL," +
                "next_attempt_at=NOW(),last_error='Worker lease expired',updated_at=NOW() " +
                "WHERE status='running' AND lease_expires_at<NOW()");
        List<Long> claimed = jdbc.queryForList("""
                WITH candidates AS (
                    SELECT id FROM workspace_execution
                    WHERE status IN ('queued','retrying') AND next_attempt_at<=NOW()
                    ORDER BY started_at FOR UPDATE SKIP LOCKED LIMIT 10
                )
                UPDATE workspace_execution execution
                SET status='running',worker_id=?,lease_expires_at=NOW()+INTERVAL '5 minutes',
                    attempt_count=attempt_count+1,updated_at=NOW()
                FROM candidates WHERE execution.id=candidates.id RETURNING execution.id
                """, Long.class, workerId);
        for (Long executionId : claimed) {
            try {
                executeWorkflow(executionId, workerId);
            } catch (Exception exception) {
                failOrRetry(executionId, workerId, exception);
            }
        }
    }

    private void executeWorkflow(Long executionId, String workerId) {
        Map<String, Object> execution = jdbc.queryForMap("""
                SELECT execution.id,execution.tenant_id,execution.created_by,execution.current_step,
                       execution.input::text AS input,execution.result::text AS result,
                       resource.id AS workflow_id,resource.name AS workflow_name,resource.config::text AS config
                FROM workspace_execution execution JOIN workspace_resource resource ON resource.id=execution.resource_id
                WHERE execution.id=? AND execution.worker_id=?
                """, executionId, workerId);
        long tenantId = ((Number) execution.get("tenant_id")).longValue();
        Map<String, Object> input = parseMap(execution.get("input"));
        Map<String, Object> result = parseMap(execution.get("result"));
        List<Map<String, Object>> steps = mutableNodes(result.get("steps"));
        List<Map<String, Object>> nodes = mutableNodes(parseMap(execution.get("config")).get("nodes"));
        int index = ((Number) execution.get("current_step")).intValue();
        for (; index < nodes.size(); index++) {
            Map<String, Object> node = nodes.get(index);
            String nodeId = text(node.getOrDefault("id", index + 1));
            String type = text(node.getOrDefault("type", "unknown"));
            if ("approval".equals(type) && !approved(executionId, nodeId)) {
                Map<String, Object> waiting = step(node, index, "waiting_for_approval", Map.of());
                replaceStep(steps, index, waiting);
                persistResult(executionId, workerId, index, "waiting_for_approval", execution, steps, null);
                event(executionId, nodeId, "waiting_for_approval", waiting);
                return;
            }
            event(executionId, nodeId, "node_started", Map.of("type", type, "order", index + 1));
            Object output = executeNode(executionId, tenantId, execution, input, node, 0);
            Map<String, Object> completed = step(node, index, "completed", output);
            replaceStep(steps, index, completed);
            event(executionId, nodeId, "node_completed", completed);
            persistResult(executionId, workerId, index + 1, "running", execution, steps, null);
        }
        persistResult(executionId, workerId, nodes.size(), "completed", execution, steps, null);
        event(executionId, "", "completed", Map.of("stepCount", nodes.size()));
    }

    private Object executeNode(Long executionId, long tenantId, Map<String, Object> execution,
                               Map<String, Object> input, Map<String, Object> node, int depth) {
        if (depth > 5) throw new IllegalStateException("Subflow nesting exceeds five levels");
        String type = text(node.getOrDefault("type", "unknown"));
        return switch (type) {
            case "entry", "branch", "output" -> input;
            case "agent" -> executeAgent(executionId, tenantId, execution, input, node);
            case "tool" -> executeTool(tenantId, input, node);
            case "subflow" -> executeSubflow(executionId, tenantId, execution, input, node, depth + 1);
            case "approval" -> Map.of("approved", true);
            default -> throw new IllegalArgumentException("Unsupported workflow node type: " + type);
        };
    }

    private String executeAgent(Long executionId, long tenantId, Map<String, Object> execution,
                                Map<String, Object> input, Map<String, Object> node) {
        long agentId = number(node.get("agentId"), number(input.get("agentId"), -1));
        if (agentId < 1) throw new IllegalArgumentException("Agent node requires agentId");
        Integer agents = jdbc.queryForObject("SELECT COUNT(*) FROM agent_definition WHERE id=? AND tenant_id=? AND status='published'",
                Integer.class, agentId, tenantId);
        if (agents == null || agents == 0) throw new IllegalArgumentException("Published Agent not found in current tenant");
        String message = text(node.get("prompt"));
        if (message.isBlank()) message = text(input.get("message"));
        if (message.isBlank()) message = json(input);
        StringBuilder reply = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> runtimeError = new AtomicReference<>();
        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId("workflow-" + executionId + "-" + text(node.get("id")))
                .setAgentId(String.valueOf(agentId)).setTenantId(String.valueOf(tenantId))
                .setUserId(text(execution.get("created_by"))).setMessage(message).setChannel("workflow").build();
        runtime.executeAgent(request, response -> {
            if (response.getType() == ExecutionResponse.Type.TEXT) reply.append(response.getContent());
            if (response.getType() == ExecutionResponse.Type.ERROR) runtimeError.set(response.getContent());
        }, failure::set, () -> { });
        if (failure.get() != null) throw new IllegalStateException("Agent execution failed", failure.get());
        if (runtimeError.get() != null) throw new IllegalStateException(runtimeError.get());
        return reply.toString();
    }

    private Object executeTool(long tenantId, Map<String, Object> input, Map<String, Object> node) {
        String toolName = text(node.get("toolName"));
        if (toolName.isBlank()) toolName = text(node.get("toolCode"));
        if (toolName.isBlank()) toolName = text(node.get("detail"));
        if (toolName.isBlank()) throw new IllegalArgumentException("Tool node requires toolName");
        Map<String, Object> arguments = new LinkedHashMap<>(asMap(node.get("arguments")));
        if (arguments.isEmpty()) arguments.putAll(asMap(input.get("arguments")));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(runtimeUrl + "/internal/tools/execute"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .header("X-Internal-Token", internalToken).header("X-Tenant-Id", String.valueOf(tenantId))
                    .POST(HttpRequest.BodyPublishers.ofString(json(Map.of("toolName", toolName, "arguments", arguments))))
                    .build();
            HttpResponse<String> raw = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (raw.statusCode() != 200) throw new IllegalStateException("Tool runtime returned HTTP " + raw.statusCode());
            Map<String, Object> response = parseMap(raw.body());
            if (!"ok".equals(response.get("status"))) throw new IllegalStateException("Tool execution failed");
            return response.get("result");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tool execution interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Tool execution request failed", exception);
        }
    }

    private Object executeSubflow(Long executionId, long tenantId, Map<String, Object> execution,
                                  Map<String, Object> input, Map<String, Object> node, int depth) {
        long templateId = number(node.get("templateId"), -1);
        if (templateId < 1) throw new IllegalArgumentException("Subflow node requires templateId");
        Map<String, Object> template = getTemplate(tenantId, templateId);
        List<Object> outputs = new ArrayList<>();
        for (Map<String, Object> child : mutableNodes(asMap(template.get("config")).get("nodes"))) {
            if ("approval".equals(text(child.get("type")))) {
                throw new IllegalStateException("Approval nodes in subflows must be promoted to the parent workflow");
            }
            outputs.add(executeNode(executionId, tenantId, execution, input, child, depth));
        }
        return outputs;
    }

    private void persistResult(Long executionId, String workerId, int currentStep, String status,
                               Map<String, Object> execution, List<Map<String, Object>> steps, String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", execution.get("workflow_id"));
        result.put("workflowName", execution.get("workflow_name"));
        result.put("steps", steps);
        result.put("status", status);
        jdbc.update("UPDATE workspace_execution SET status=?,result=?::jsonb,current_step=?,last_error=?," +
                        "worker_id=CASE WHEN ? IN ('running') THEN worker_id ELSE NULL END," +
                        "lease_expires_at=CASE WHEN ? IN ('running') THEN lease_expires_at ELSE NULL END," +
                        "completed_at=CASE WHEN ? IN ('completed','failed','cancelled') THEN NOW() ELSE completed_at END,updated_at=NOW() " +
                        "WHERE id=? AND worker_id=?",
                status, json(result), currentStep, error, status, status, status, executionId, workerId);
    }

    private void failOrRetry(Long executionId, String workerId, Exception exception) {
        String error = safe(exception);
        jdbc.update("UPDATE workspace_execution SET status=CASE WHEN attempt_count>=max_attempts THEN 'failed' ELSE 'retrying' END," +
                        "next_attempt_at=NOW()+(LEAST(300,POWER(2,attempt_count)*5)*INTERVAL '1 second'),last_error=?," +
                        "worker_id=NULL,lease_expires_at=NULL,completed_at=CASE WHEN attempt_count>=max_attempts THEN NOW() ELSE NULL END," +
                        "updated_at=NOW() WHERE id=? AND worker_id=?",
                error, executionId, workerId);
        event(executionId, "", "execution_error", Map.of("error", error));
    }

    private boolean approved(Long executionId, String nodeId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_execution_event WHERE execution_id=? AND node_id=? AND event_type='approve'",
                Integer.class, executionId, nodeId);
        return count != null && count > 0;
    }

    private void event(Long executionId, String nodeId, String type, Object payload) {
        jdbc.update("INSERT INTO workflow_execution_event(execution_id,node_id,event_type,payload) VALUES (?,?,?,?::jsonb)",
                executionId, nodeId.isBlank() ? null : nodeId, type, json(payload));
    }

    private Map<String, Object> step(Map<String, Object> node, int index, String status, Object output) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("order", index + 1);
        step.put("nodeId", node.getOrDefault("id", index + 1));
        step.put("type", text(node.get("type")));
        step.put("title", text(node.getOrDefault("title", "Untitled node")));
        step.put("status", status);
        step.put("output", output == null ? Map.of() : output);
        return step;
    }

    private void replaceStep(List<Map<String, Object>> steps, int index, Map<String, Object> step) {
        while (steps.size() <= index) steps.add(new LinkedHashMap<>());
        steps.set(index, step);
    }

    private Map<String, Object> normalizeExecution(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("result", parseMap(row.get("result")));
        result.put("executionId", row.get("id"));
        return result;
    }

    private long number(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private String safe(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) message = throwable.getClass().getSimpleName();
        return message.length() <= 1000 ? message : message.substring(0, 1000);
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
