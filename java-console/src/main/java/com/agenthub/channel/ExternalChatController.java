package com.agenthub.channel;

import com.agenthub.audit.service.AuditService;
import com.agenthub.common.response.ApiResponse;
import com.agenthub.grpc.PythonAgentClient;
import com.agenthub.grpc.stub.ExecutionRequest;
import com.agenthub.grpc.stub.ExecutionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v1")
public class ExternalChatController {

    private static final Logger log = LoggerFactory.getLogger(ExternalChatController.class);
    private final PythonAgentClient pythonAgentClient;
    private final AuditService auditService;
    private final ApiKeyController apiKeyController;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ExternalChatController(PythonAgentClient pythonAgentClient,
                                   AuditService auditService,
                                   ApiKeyController apiKeyController) {
        this.pythonAgentClient = pythonAgentClient;
        this.auditService = auditService;
        this.apiKeyController = apiKeyController;
    }

    /**
     * 外部 API 对话（API Key 认证）
     * POST /api/v1/chat
     * Header: X-API-Key: ak-xxx
     * Body: {"agentId": 1, "message": "Hello"}
     */
    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestBody Map<String, Object> body) {

        // 认证
        Map<String, Object> keyInfo = apiKeyController.authenticate(apiKey);
        if (keyInfo == null) {
            return ApiResponse.error(401, "Invalid API Key");
        }

        String message = (String) body.getOrDefault("message", "");
        Object agentIdObj = body.getOrDefault("agentId", keyInfo.get("agent_id"));
        String agentId = agentIdObj != null ? agentIdObj.toString() : "1";
        String sessionId = (String) body.getOrDefault("sessionId", UUID.randomUUID().toString());

        StringBuilder fullText = new StringBuilder();

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId(sessionId)
                .setAgentId(agentId)
                .setUserId(keyInfo.get("user_id").toString())
                .setMessage(message)
                .setChannel("api")
                .build();

        pythonAgentClient.executeAgent(
                request,
                response -> {
                    if (response.getType() == ExecutionResponse.Type.TEXT) {
                        fullText.append(response.getContent());
                    }
                },
                error -> log.error("API chat error", error),
                () -> log.debug("API chat completed")
        );

        auditService.record("api_chat", Long.valueOf(keyInfo.get("user_id").toString()),
                "api_user", "API chat: agent=" + agentId, message, "success");

        return ApiResponse.ok(Map.of(
                "sessionId", sessionId,
                "reply", fullText.toString()
        ));
    }

    /**
     * 外部 SSE 流式对话
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(
            @RequestHeader("X-API-Key") String apiKey,
            @RequestBody Map<String, Object> body) {

        Map<String, Object> keyInfo = apiKeyController.authenticate(apiKey);
        if (keyInfo == null) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("Invalid API Key"));
            return emitter;
        }

        String message = (String) body.getOrDefault("message", "");
        Object agentIdObj = body.getOrDefault("agentId", keyInfo.get("agent_id"));
        String agentId = agentIdObj != null ? agentIdObj.toString() : "1";
        String sessionId = (String) body.getOrDefault("sessionId", UUID.randomUUID().toString());

        SseEmitter emitter = new SseEmitter(300_000L);

        ExecutionRequest request = ExecutionRequest.newBuilder()
                .setSessionId(sessionId)
                .setAgentId(agentId)
                .setUserId(keyInfo.get("user_id").toString())
                .setMessage(message)
                .setChannel("api")
                .build();

        executor.submit(() -> {
            pythonAgentClient.executeAgent(
                    request,
                    response -> {
                        try {
                            String type = response.getType().name().toLowerCase();
                            emitter.send(SseEmitter.event().name(type).data(response.getContent()));
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    error -> {
                        try {
                            emitter.send(SseEmitter.event().name("error").data(error.getMessage()));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    },
                    () -> {
                        try {
                            emitter.send(SseEmitter.event().name("done").data(""));
                            emitter.complete();
                        } catch (Exception e) {
                            emitter.completeWithError(e);
                        }
                    }
            );
        });

        return emitter;
    }
}
