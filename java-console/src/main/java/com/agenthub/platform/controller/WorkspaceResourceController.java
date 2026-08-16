package com.agenthub.platform.controller;

import com.agenthub.common.response.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import com.agenthub.security.CurrentUser;
import com.agenthub.platform.service.WebhookUrlValidator;
import com.agenthub.platform.service.WorkspaceResourceQueryService;
import com.agenthub.platform.dto.WorkspaceResourceRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceResourceController {

    private static final Set<String> RESOURCE_TYPES = Set.of("workflow", "guardrail", "channel", "routing");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();
    private final CurrentUser currentUser;
    private final WebhookUrlValidator webhookUrlValidator;
    private final WorkspaceResourceQueryService queryService;

    public WorkspaceResourceController(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                       CurrentUser currentUser, WebhookUrlValidator webhookUrlValidator,
                                       WorkspaceResourceQueryService queryService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.currentUser = currentUser;
        this.webhookUrlValidator = webhookUrlValidator;
        this.queryService = queryService;
    }

    @GetMapping("/{type}")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable String type, Pageable pageable) {
        validateType(type);
        return ApiResponse.ok(queryService.list(currentUser.tenantId(), type, pageable));
    }

    @PostMapping("/{type}")
    public ApiResponse<Map<String, Object>> create(
            @PathVariable String type,
            @Valid @RequestBody WorkspaceResourceRequest request) {
        validateType(type);
        String key = type + "-" + UUID.randomUUID();
        String name = text(request.name());
        String description = text(request.description());
        String status = text(request.status() == null ? "draft" : request.status());
        String config = toJson(request.config() == null ? Map.of() : request.config());
        Long id = jdbc.queryForObject(
                "INSERT INTO workspace_resource (tenant_id, resource_type, resource_key, name, description, config, status, created_by) " +
                        "VALUES (?,?,?,?,?,?::jsonb,?,?) RETURNING id",
                Long.class, currentUser.tenantId(), type, key, name, description, config, status, currentUser.userId()
        );
        return get(type, id);
    }

    @GetMapping("/{type}/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String type, @PathVariable Long id) {
        validateType(type);
        try {
            return ApiResponse.ok(queryService.get(currentUser.tenantId(), type, id));
        } catch (NoSuchElementException exception) {
            return ApiResponse.error(404, "Resource not found");
        }
    }

    @PutMapping("/{type}/{id}")
    public ApiResponse<Map<String, Object>> update(
            @PathVariable String type,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        validateType(type);
        Map<String, Object> current = getResource(type, id);
        String name = text(body.getOrDefault("name", current.get("name")));
        String description = text(body.getOrDefault("description", current.get("description")));
        String status = text(body.getOrDefault("status", current.get("status")));
        Object configValue = body.containsKey("config") ? body.get("config") : current.get("config");
        jdbc.update(
                "UPDATE workspace_resource SET name = ?, description = ?, config = ?::jsonb, status = ?, updated_at = NOW() " +
                        "WHERE id = ? AND tenant_id = ?",
                name, description, toJson(configValue), status, id, currentUser.tenantId()
        );
        return get(type, id);
    }

    @DeleteMapping("/{type}/{id}")
    public ApiResponse<String> delete(@PathVariable String type, @PathVariable Long id) {
        validateType(type);
        int affected = jdbc.update("DELETE FROM workspace_resource WHERE id = ? AND tenant_id = ? AND resource_type = ?",
                id, currentUser.tenantId(), type);
        return affected == 0 ? ApiResponse.error(404, "Resource not found") : ApiResponse.ok("Resource deleted");
    }

    @PostMapping("/workflow/{id}/run")
    @Transactional
    public ApiResponse<Map<String, Object>> runWorkflow(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> resource = getResource("workflow", id);
        Map<String, Object> config = asMap(resource.get("config"));
        Object rawNodes = config.get("nodes");
        if (!(rawNodes instanceof List<?> nodes) || nodes.isEmpty()) {
            return ApiResponse.error(400, "Workflow has no executable nodes");
        }

        String idempotencyKey = body == null ? null : text(body.get("idempotencyKey"));
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            List<Map<String, Object>> existing = jdbc.queryForList(
                    "SELECT id, status, result::text AS result, started_at FROM workspace_execution " +
                            "WHERE tenant_id=? AND resource_id=? AND idempotency_key=?", currentUser.tenantId(), id, idempotencyKey);
            if (!existing.isEmpty()) return ApiResponse.ok(normalizeExecution(existing.get(0)));
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        int order = 1;
        for (Object rawNode : nodes) {
            Map<String, Object> node = asMap(rawNode);
            String nodeType = text(node.getOrDefault("type", "unknown"));
            String title = text(node.getOrDefault("title", "Untitled node"));
            String stepStatus = switch (nodeType) {
                case "entry", "output", "branch" -> "completed";
                case "approval" -> "waiting_for_approval";
                case "agent", "tool", "subflow" -> "queued";
                default -> "failed";
            };
            steps.add(Map.of(
                    "order", order++,
                    "nodeId", node.getOrDefault("id", order),
                    "type", nodeType,
                    "title", title,
                    "status", stepStatus
            ));
            if (!"completed".equals(stepStatus)) {
                break;
            }
        }
        String executionStatus = text(steps.get(steps.size() - 1).get("status"));
        Map<String, Object> result = Map.of(
                "workflowId", id,
                "workflowName", resource.get("name"),
                "steps", steps,
                "status", executionStatus
        );
        Long executionId = jdbc.queryForObject(
                "INSERT INTO workspace_execution (resource_id, tenant_id, execution_type, status, result, input, " +
                        "current_step, idempotency_key, created_by, completed_at) VALUES (?,?,'workflow_run',?,?::jsonb,?::jsonb,?,?,?," +
                        "CASE WHEN ? = 'completed' THEN NOW() ELSE NULL END) RETURNING id",
                Long.class, id, currentUser.tenantId(), executionStatus, toJson(result),
                toJson(body == null ? Map.of() : body), steps.size() - 1,
                idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey,
                currentUser.userId(), executionStatus
        );
        jdbc.update("INSERT INTO workflow_execution_event(execution_id,event_type,payload) VALUES (?,'started',?::jsonb)",
                executionId, toJson(result));
        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("executionId", executionId);
        response.put("startedAt", LocalDateTime.now().toString());
        return ApiResponse.ok(response);
    }

    @PostMapping("/workflow/executions/{executionId}/transition")
    @Transactional
    public ApiResponse<Map<String, Object>> transitionWorkflow(@PathVariable Long executionId,
            @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT we.id, we.status, we.result::text AS result FROM workspace_execution we " +
                        "JOIN workspace_resource wr ON wr.id=we.resource_id WHERE we.id=? AND we.tenant_id=? AND wr.tenant_id=? FOR UPDATE",
                executionId, currentUser.tenantId(), currentUser.tenantId());
        if (rows.isEmpty()) return ApiResponse.error(404, "Execution not found");
        String action = text(body.get("action"));
        String status = switch (action) {
            case "complete_node" -> "queued";
            case "approve", "retry" -> "queued";
            case "reject", "fail" -> "failed";
            case "cancel" -> "cancelled";
            default -> throw new IllegalArgumentException("Unsupported transition: " + action);
        };
        jdbc.update("UPDATE workspace_execution SET status=?, updated_at=NOW(), completed_at=" +
                        "CASE WHEN ? IN ('failed','cancelled') THEN NOW() ELSE NULL END WHERE id=?",
                status, status, executionId);
        jdbc.update("INSERT INTO workflow_execution_event(execution_id,node_id,event_type,payload) VALUES (?,?,?,?::jsonb)",
                executionId, text(body.get("nodeId")), action, toJson(body));
        return ApiResponse.ok(Map.of("executionId", executionId, "status", status));
    }

    @GetMapping("/workflow/{id}/executions")
    public ApiResponse<List<Map<String, Object>>> workflowExecutions(@PathVariable Long id, Pageable pageable) {
        getResource("workflow", id);
        return ApiResponse.ok(queryService.listExecutions(currentUser.tenantId(), id, pageable));
    }

    private Map<String, Object> normalizeExecution(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>(row);
        normalized.put("result", parseJson(row.get("result")));
        normalized.put("executionId", row.get("id"));
        return normalized;
    }

    @PostMapping("/guardrail/test")
    public ApiResponse<Map<String, Object>> testGuardrails(@RequestBody Map<String, Object> body) {
        String input = text(body.getOrDefault("text", ""));
        if (input.isBlank()) {
            return ApiResponse.error(400, "Text is required");
        }
        List<Map<String, Object>> policies = listEnabled("guardrail");
        List<Map<String, Object>> findings = new ArrayList<>();
        String output = input;
        for (Map<String, Object> policy : policies) {
            String key = text(policy.get("resource_key"));
            if ("pii".equals(key)) {
                if (input.matches(".*1[3-9]\\d{9}.*")) {
                    findings.add(finding("个人隐私", "medium", "检测到中国大陆手机号，输出已脱敏"));
                    output = output.replaceAll("(1[3-9]\\d)\\d{4}(\\d{4})", "$1****$2");
                }
                if (input.matches(".*[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}.*")) {
                    findings.add(finding("个人隐私", "medium", "检测到邮箱地址"));
                    output = output.replaceAll("([A-Za-z0-9._%+-]{2})[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+)", "$1***$2");
                }
            }
            if ("prompt".equals(key) && input.matches("(?s).*(忽略|无视).*(之前|系统|指令).*")) {
                findings.add(finding("提示词攻击", "high", "检测到试图覆盖系统指令的表达"));
            }
            Map<String, Object> config = asMap(policy.get("config"));
            String customPattern = text(config.get("pattern"));
            if (!customPattern.isBlank()) {
                try {
                    if (Pattern.compile(customPattern, Pattern.CASE_INSENSITIVE).matcher(input).find()) {
                        findings.add(finding(
                                text(policy.getOrDefault("name", "自定义规则")),
                                text(config.getOrDefault("level", "medium")),
                                text(config.getOrDefault("detail", "命中自定义检查规则"))
                        ));
                    }
                } catch (PatternSyntaxException exception) {
                    return ApiResponse.error(400, "规则“" + policy.get("name") + "”的正则表达式无效");
                }
            }
        }
        String decision = findings.stream().anyMatch(item -> "high".equals(item.get("level"))) ? "block" :
                findings.isEmpty() ? "pass" : "sanitize";
        return ApiResponse.ok(Map.of(
                "decision", decision,
                "findings", findings,
                "safeOutput", output,
                "checkedPolicies", policies.size()
        ));
    }

    @PostMapping("/guardrail/publish")
    public ApiResponse<Map<String, Object>> publishGuardrails() {
        int updated = jdbc.update(
                "UPDATE workspace_resource SET status = CASE WHEN COALESCE((config->>'enabled')::boolean, false) " +
                        "THEN 'published' ELSE 'inactive' END, updated_at = NOW() " +
                        "WHERE tenant_id = ? AND resource_type = 'guardrail'", currentUser.tenantId()
        );
        return ApiResponse.ok(Map.of("published", updated, "publishedAt", LocalDateTime.now().toString()));
    }

    @PostMapping("/channel/{id}/test")
    public ApiResponse<Map<String, Object>> testChannel(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> resource = getResource("channel", id);
        Map<String, Object> config = asMap(resource.get("config"));
        String type = text(resource.get("resource_key"));
        String message = body == null ? "AgentHub channel test" : text(body.getOrDefault("message", "AgentHub channel test"));
        if (Set.of("web", "api").contains(type)) {
            return ApiResponse.ok(Map.of("channel", type, "status", "ready", "message", "Local channel configuration is valid"));
        }
        String webhookUrl = text(config.getOrDefault("webhookUrl", ""));
        if (webhookUrl.isBlank()) {
            return ApiResponse.error(400, "Webhook URL is required before testing " + resource.get("name"));
        }
        try {
            var safeWebhookUri = webhookUrlValidator.validate(webhookUrl);
            Object payload = switch (type) {
                case "dingtalk" -> Map.of("msgtype", "text", "text", Map.of("content", message));
                case "feishu" -> Map.of("msg_type", "text", "content", Map.of("text", message));
                default -> Map.of("text", message);
            };
            ResponseEntity<Void> response = restClient.post().uri(safeWebhookUri).body(payload).retrieve().toBodilessEntity();
            return ApiResponse.ok(Map.of("channel", type, "status", "delivered", "httpStatus", response.getStatusCode().value()));
        } catch (Exception exception) {
            return ApiResponse.error(502, "Channel delivery failed");
        }
    }

    private List<Map<String, Object>> listEnabled(String type) {
        return queryService.listEnabled(currentUser.tenantId(), type);
    }

    private Map<String, Object> getResource(String type, Long id) {
        return queryService.get(currentUser.tenantId(), type, id);
    }

    private Map<String, Object> finding(String type, String level, String detail) {
        return Map.of("type", type, "level", level, "detail", detail);
    }

    private Object parseJson(Object value) {
        if (value == null) return Map.of();
        try {
            return objectMapper.readValue(value.toString(), Object.class);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid stored JSON", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, new TypeReference<>() {});
        }
        return Map.of();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to serialize configuration", exception);
        }
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private void validateType(String type) {
        if (!RESOURCE_TYPES.contains(type)) {
            throw new IllegalArgumentException("Unsupported resource type: " + type);
        }
    }
}
