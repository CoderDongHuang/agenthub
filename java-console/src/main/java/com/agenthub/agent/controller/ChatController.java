package com.agenthub.agent.controller;

import com.agenthub.audit.service.AuditService;
import com.agenthub.common.response.ApiResponse;
import com.agenthub.grpc.PythonAgentClient;
import com.agenthub.grpc.stub.ExecutionRequest;
import com.agenthub.grpc.stub.ExecutionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.UUID;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.agenthub.security.CurrentUser;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import com.agenthub.release.service.AgentVersionService;
import com.agenthub.routing.service.ModelRoutingService;
import com.agenthub.observability.service.TraceService;

@RestController
@RequestMapping("/api/agents")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final PythonAgentClient pythonAgentClient;
    private final AuditService auditService;
    private final ExecutorService executor;
    private final CurrentUser currentUser;
    private final AgentVersionService versionService;
    private final ModelRoutingService routingService;
    private final TraceService traceService;

    public ChatController(PythonAgentClient pythonAgentClient, AuditService auditService,
                          JdbcTemplate jdbcTemplate, CurrentUser currentUser,
                          AgentVersionService versionService, ModelRoutingService routingService,
                          TraceService traceService,
                          @Value("${agenthub.chat.max-concurrency:16}") int maxConcurrency,
                          @Value("${agenthub.chat.queue-capacity:64}") int queueCapacity) {
        this.pythonAgentClient = pythonAgentClient;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
        this.versionService = versionService;
        this.routingService = routingService;
        this.traceService = traceService;
        this.executor = new ThreadPoolExecutor(
                maxConcurrency, maxConcurrency, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private final JdbcTemplate jdbcTemplate;

    @PostMapping(value = "/{agentId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long agentId, @RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());
        String userId = String.valueOf(currentUser.userId());
        Long tenantNumber = currentUser.tenantId();
        String tenantId = String.valueOf(tenantNumber);
        AgentVersionService.ResolvedVersion version = versionService.resolve(
                currentUser.tenantId(), agentId, sessionId);
        String preferredModel = String.valueOf(version.config().getOrDefault("model",
                requireAgentModel(agentId, currentUser.tenantId())));
        ModelRoutingService.RouteDecision route = routingService.route(
                currentUser.tenantId(), preferredModel, routingConstraints(body));
        String model = route.model();
        Instant traceStarted = Instant.now();
        UUID traceId = traceService.start(currentUser.tenantId(), sessionId, agentId, version.id(), model,
                route.reason(), message);
        traceService.recordOperational(traceId, 0, 0, 0,
                executor instanceof ThreadPoolExecutor pool ? pool.getQueue().size() : 0, false);

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId(sessionId)
                .setAgentId(String.valueOf(agentId))
                .setUserId(userId)
                .setTenantId(tenantId)
                .setMessage(message)
                .setChannel("web")
                .putAllVariables(runtimeOverrides(version, route, traceId))
                .build();

        StringBuffer outputText = new StringBuffer();
        AtomicBoolean executionFailed = new AtomicBoolean(false);
        AtomicBoolean auditRecorded = new AtomicBoolean(false);
        AtomicBoolean traceRecorded = new AtomicBoolean(false);
        Map<String, Instant> activeToolSpans = new ConcurrentHashMap<>();
        try {
            executor.submit(() -> {
            pythonAgentClient.executeAgent(
                    request,
                    // onResponse
                    response -> {
                        try {
                            String type = response.getType().name().toLowerCase();
                            String data = response.getContent();
                            if (response.getType() == ExecutionResponse.Type.TEXT) {
                                outputText.append(data);
                            }
                            if (response.getType() == ExecutionResponse.Type.ERROR) {
                                executionFailed.set(true);
                                log.warn("Agent runtime returned an error: agentId={}, sessionId={}, detail={}",
                                        agentId, sessionId, data);
                                data = "Agent 执行失败，请检查模型配置或稍后重试。";
                            }
                            if (response.getType() == ExecutionResponse.Type.TOOL_START) {
                                activeToolSpans.put(response.getToolName(), Instant.now());
                            } else if (response.getType() == ExecutionResponse.Type.TOOL_END) {
                                Instant started = activeToolSpans.remove(response.getToolName());
                                traceService.addSpan(traceId, "tool", response.getToolName(), "completed", Map.of(),
                                        Map.of("content", data == null ? "" : data), Map.of(),
                                        started == null ? 0 : traceService.elapsedMillis(started));
                            } else if (response.getType() == ExecutionResponse.Type.APPROVAL_WAIT) {
                                traceService.addSpan(traceId, "approval", response.getToolName(), "waiting", Map.of(),
                                        Map.of("message", data == null ? "" : data), Map.of(), 0);
                            }
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("content", data != null ? data : "");
                            payload.put("toolName", response.getToolName());
                            SseEmitter.SseEventBuilder event = SseEmitter.event()
                                    .name(type)
                                    .data(payload, MediaType.APPLICATION_JSON);
                            if (!response.getToolName().isEmpty()) {
                                event = event.id(response.getToolName());
                            }
                            emitter.send(event);
                        } catch (Exception e) {
                            log.error("SSE 发送失败", e);
                        }
                    },
                    // onError
                    error -> {
                        executionFailed.set(true);
                        reportRouteHealth(tenantNumber, route.endpointId(), false, traceService.elapsedMillis(traceStarted), error.getMessage());
                        recordExecutionAudit(auditRecorded, agentId, userId, tenantId, message, "failed");
                        completeTrace(traceRecorded, traceId, "failed", message, outputText, traceStarted);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(Map.of("content", "Agent 执行失败，请稍后重试。"), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    // onCompleted
                    () -> {
                        String result = executionFailed.get() ? "failed" : "success";
                        recordExecutionAudit(auditRecorded, agentId, userId, tenantId, message, result);
                        if (!executionFailed.get()) {
                            reportRouteHealth(tenantNumber, route.endpointId(), true, traceService.elapsedMillis(traceStarted), null);
                            Usage usage = recordTokenUsage(agentId, sessionId, userId, tenantId, model, message, outputText);
                            if (traceRecorded.compareAndSet(false, true)) {
                                traceService.complete(traceId, "success", usage.inputTokens(), usage.outputTokens(),
                                        usage.cost(), traceService.elapsedMillis(traceStarted), outputText.toString());
                            }
                        } else {
                            completeTrace(traceRecorded, traceId, "failed", message, outputText, traceStarted);
                        }
                        try {
                            emitter.send(SseEmitter.event().name("done")
                                    .data(Map.of("content", ""), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    }
            );
            });
        } catch (RejectedExecutionException exception) {
            completeTrace(traceRecorded, traceId, "failed", message, outputText, traceStarted);
            emitter.completeWithError(new IllegalStateException("Agent execution capacity is temporarily exhausted"));
        }

        return emitter;
    }

    @PreDestroy
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @PostMapping("/{agentId}/chat/simple")
    public ApiResponse<Map<String, String>> chatSimple(@PathVariable Long agentId, @RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = UUID.randomUUID().toString();
        String userId = String.valueOf(currentUser.userId());
        Long tenantNumber = currentUser.tenantId();
        String tenantId = String.valueOf(tenantNumber);
        AgentVersionService.ResolvedVersion version = versionService.resolve(
                currentUser.tenantId(), agentId, sessionId);
        String preferredModel = String.valueOf(version.config().getOrDefault("model",
                requireAgentModel(agentId, currentUser.tenantId())));
        ModelRoutingService.RouteDecision route = routingService.route(currentUser.tenantId(), preferredModel,
                routingConstraints(body));
        Instant traceStarted = Instant.now();
        UUID traceId = traceService.start(currentUser.tenantId(), sessionId, agentId, version.id(), route.model(),
                route.reason(), message);
        traceService.recordOperational(traceId, 0, 0, 0,
                executor instanceof ThreadPoolExecutor pool ? pool.getQueue().size() : 0, false);

        StringBuilder fullText = new StringBuilder();
        AtomicReference<String> executionError = new AtomicReference<>();

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId(sessionId)
                .setAgentId(String.valueOf(agentId))
                .setUserId(userId)
                .setTenantId(tenantId)
                .setMessage(message)
                .setChannel("web")
                .putAllVariables(runtimeOverrides(version, route, traceId))
                .build();

        pythonAgentClient.executeAgent(
                request,
                response -> {
                    if (response.getType() == ExecutionResponse.Type.TEXT) {
                        fullText.append(response.getContent());
                    } else if (response.getType() == ExecutionResponse.Type.ERROR) {
                        executionError.compareAndSet(null, response.getContent());
                    }
                },
                error -> {
                    log.error("Chat error", error);
                    executionError.compareAndSet(null, error.getMessage());
                },
                () -> log.debug("Chat completed")
        );

        if (executionError.get() != null) {
            reportRouteHealth(tenantNumber, route.endpointId(), false, traceService.elapsedMillis(traceStarted), executionError.get());
            traceService.complete(traceId, "failed", tokenEstimate(message), tokenEstimate(fullText), BigDecimal.ZERO,
                    traceService.elapsedMillis(traceStarted), fullText.toString());
            throw new IllegalStateException("Agent execution failed");
        }

        Usage usage = recordTokenUsage(agentId, sessionId, userId, tenantId, route.model(), message, fullText);
        reportRouteHealth(tenantNumber, route.endpointId(), true, traceService.elapsedMillis(traceStarted), null);
        traceService.complete(traceId, "success", usage.inputTokens(), usage.outputTokens(), usage.cost(),
                traceService.elapsedMillis(traceStarted), fullText.toString());

        return ApiResponse.ok(Map.of(
                "sessionId", sessionId,
                "reply", fullText.toString()
        ));
    }

    private String requireAgentModel(Long agentId, Long tenantId) {
        var models = jdbcTemplate.queryForList(
                "SELECT model FROM agent_definition WHERE id = ? AND tenant_id = ?",
                String.class, agentId, tenantId
        );
        if (models.isEmpty()) throw new IllegalArgumentException("Agent not found");
        return models.get(0);
    }

    private void recordExecutionAudit(AtomicBoolean recorded, Long agentId, String userId,
                                      String tenantId, String message, String result) {
        if (recorded.compareAndSet(false, true)) {
            auditService.record("agent_execute", Long.valueOf(userId), "user",
                    "Agent chat: agent=" + agentId, message, result, Long.valueOf(tenantId));
        }
    }

    private Usage recordTokenUsage(Long agentId, String sessionId, String userId, String tenantId,
                                  String model, String message, CharSequence outputText) {
        int inputTokens = tokenEstimate(message);
        int outputTokens = tokenEstimate(outputText);
        BigDecimal cost = BigDecimal.valueOf((inputTokens + outputTokens) * 0.000002);
        try {
            jdbcTemplate.update(
                    "INSERT INTO token_usage (tenant_id, agent_id, session_id, user_id, model, input_tokens, output_tokens, cost) " +
                            "VALUES (?,?,?,?,?,?,?,?)",
                    Long.valueOf(tenantId), agentId, sessionId, Long.valueOf(userId),
                    model, inputTokens, outputTokens, cost
            );
        } catch (Exception exception) {
            log.warn("Token usage persistence failed: agentId={}, sessionId={}", agentId, sessionId, exception);
        }
        return new Usage(inputTokens, outputTokens, cost);
    }

    private Map<String, String> runtimeOverrides(AgentVersionService.ResolvedVersion version,
                                                  ModelRoutingService.RouteDecision route, UUID traceId) {
        Map<String, String> variables = new HashMap<>();
        variables.put("model_override", route.model());
        variables.put("route_reason", route.reason());
        variables.put("trace_id", traceId.toString());
        variables.put("agent_version", String.valueOf(version.version()));
        putIfPresent(variables, "agent_name_override", version.config().get("name"));
        putIfPresent(variables, "system_prompt_override", version.config().get("systemPrompt"));
        putIfPresent(variables, "temperature_override", version.config().get("temperature"));
        putIfPresent(variables, "max_tokens_override", version.config().get("maxTokens"));
        return variables;
    }

    private Map<String, Object> routingConstraints(Map<String, String> body) {
        Map<String, Object> constraints = new HashMap<>();
        for (String key : new String[]{"region", "maxCostPer1k", "minQuality", "maxLatencyMs"}) {
            String value = body.get(key);
            if (value != null && !value.isBlank()) constraints.put(key, value);
        }
        return constraints;
    }

    private void putIfPresent(Map<String, String> values, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) values.put(key, String.valueOf(value));
    }

    private void completeTrace(AtomicBoolean recorded, UUID traceId, String status, String message,
                               CharSequence output, Instant started) {
        if (recorded.compareAndSet(false, true)) {
            traceService.complete(traceId, status, tokenEstimate(message), tokenEstimate(output), BigDecimal.ZERO,
                    traceService.elapsedMillis(started), output.toString());
        }
    }

    private int tokenEstimate(CharSequence value) {
        return value == null ? 0 : value.length() / 3 + 1;
    }

    private void reportRouteHealth(Long tenantId, Long endpointId, boolean success, long latencyMs, String error) {
        if (endpointId == null) return;
        try {
            routingService.report(tenantId, endpointId, success, latencyMs, error);
        } catch (Exception exception) {
            log.warn("Model endpoint health report failed: endpointId={}", endpointId, exception);
        }
    }

    private record Usage(int inputTokens, int outputTokens, BigDecimal cost) {}
}
