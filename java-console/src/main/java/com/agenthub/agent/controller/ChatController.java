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
import java.util.concurrent.Executors;
import com.agenthub.security.CurrentUser;

@RestController
@RequestMapping("/api/agents")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final PythonAgentClient pythonAgentClient;
    private final AuditService auditService;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CurrentUser currentUser;

    public ChatController(PythonAgentClient pythonAgentClient, AuditService auditService,
                          JdbcTemplate jdbcTemplate, CurrentUser currentUser) {
        this.pythonAgentClient = pythonAgentClient;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
        this.currentUser = currentUser;
    }

    private final JdbcTemplate jdbcTemplate;

    @PostMapping(value = "/{agentId}/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable Long agentId, @RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());
        String userId = String.valueOf(currentUser.userId());
        String tenantId = String.valueOf(currentUser.tenantId());

        SseEmitter emitter = new SseEmitter(300_000L); // 5 分钟超时

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId(sessionId)
                .setAgentId(String.valueOf(agentId))
                .setUserId(userId)
                .setTenantId(tenantId)
                .setMessage(message)
                .setChannel("web")
                .build();

        StringBuilder outputText = new StringBuilder();
        executor.submit(() -> {
            pythonAgentClient.executeAgent(
                    request,
                    // onResponse
                    response -> {
                        try {
                            String type = response.getType().name().toLowerCase();
                            String data = response.getContent();
                            if ("text".equals(type)) {
                                outputText.append(data);
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
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(Map.of("content", "Agent 执行异常: " + error.getMessage()), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    // onCompleted
                    () -> {
                        // 记录审计日志
                        auditService.record("agent_execute", Long.valueOf(userId), "user",
                                "Agent chat: agent=" + agentId, message, "success", Long.valueOf(tenantId));
                        // 记录 Token 消耗（粗略估算: 英文 4 字符≈1 token, 中文 2 字符≈1 token）
                        int inputTokens = message.length() / 3 + 1;
                        int outputTokens = outputText.length() / 3 + 1;
                        double cost = (inputTokens + outputTokens) * 0.000002; // 约 $2/1M tokens
                        try {
                            jdbcTemplate.update(
                                "INSERT INTO token_usage (tenant_id, agent_id, session_id, user_id, model, input_tokens, output_tokens, cost) " +
                                        "VALUES (?,?,?,?,?,?,?,?)",
                                Long.valueOf(tenantId), Long.valueOf(agentId), sessionId, Long.valueOf(userId),
                                "deepseek-v3", inputTokens, outputTokens, cost
                            );
                        } catch (Exception ignored) {}
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

        return emitter;
    }

    @PostMapping("/{agentId}/chat/simple")
    public ApiResponse<Map<String, String>> chatSimple(@PathVariable Long agentId, @RequestBody Map<String, String> body) {
        String message = body.get("message");
        String sessionId = UUID.randomUUID().toString();
        String userId = String.valueOf(currentUser.userId());
        String tenantId = String.valueOf(currentUser.tenantId());

        StringBuilder fullText = new StringBuilder();

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
                    }
                },
                error -> log.error("Chat error", error),
                () -> log.debug("Chat completed")
        );

        return ApiResponse.ok(Map.of(
                "sessionId", sessionId,
                "reply", fullText.toString()
        ));
    }
}
