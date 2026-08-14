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
import java.util.UUID;
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

@RestController
@RequestMapping("/api/agents")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final PythonAgentClient pythonAgentClient;
    private final AuditService auditService;
    private final ExecutorService executor;
    private final CurrentUser currentUser;

    public ChatController(PythonAgentClient pythonAgentClient, AuditService auditService,
                          JdbcTemplate jdbcTemplate, CurrentUser currentUser,
                          @Value("${agenthub.chat.max-concurrency:16}") int maxConcurrency,
                          @Value("${agenthub.chat.queue-capacity:64}") int queueCapacity) {
        this.pythonAgentClient = pythonAgentClient;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
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
        String tenantId = String.valueOf(currentUser.tenantId());
        String model = requireAgentModel(agentId, currentUser.tenantId());

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId(sessionId)
                .setAgentId(String.valueOf(agentId))
                .setUserId(userId)
                .setTenantId(tenantId)
                .setMessage(message)
                .setChannel("web")
                .build();

        StringBuffer outputText = new StringBuffer();
        AtomicBoolean executionFailed = new AtomicBoolean(false);
        AtomicBoolean auditRecorded = new AtomicBoolean(false);
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
                        recordExecutionAudit(auditRecorded, agentId, userId, tenantId, message, "failed");
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
                            recordTokenUsage(agentId, sessionId, userId, tenantId, model, message, outputText);
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
        String tenantId = String.valueOf(currentUser.tenantId());
        requireAgentModel(agentId, currentUser.tenantId());

        StringBuilder fullText = new StringBuilder();
        AtomicReference<String> executionError = new AtomicReference<>();

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId(sessionId)
                .setAgentId(String.valueOf(agentId))
                .setUserId(userId)
                .setTenantId(tenantId)
                .setMessage(message)
                .setChannel("web")
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
            throw new IllegalStateException("Agent execution failed");
        }

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

    private void recordTokenUsage(Long agentId, String sessionId, String userId, String tenantId,
                                  String model, String message, CharSequence outputText) {
        int inputTokens = message.length() / 3 + 1;
        int outputTokens = outputText.length() / 3 + 1;
        double cost = (inputTokens + outputTokens) * 0.000002;
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
    }
}
