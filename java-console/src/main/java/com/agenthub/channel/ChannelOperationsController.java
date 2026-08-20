package com.agenthub.channel;

import com.agenthub.audit.service.AuditService;
import com.agenthub.common.response.ApiResponse;
import com.agenthub.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/channel/operations")
public class ChannelOperationsController {
    private final ChannelOperationsService operations;
    private final CurrentUser user;
    private final AuditService audit;

    public ChannelOperationsController(ChannelOperationsService operations, CurrentUser user, AuditService audit) {
        this.operations = operations;
        this.user = user;
        this.audit = audit;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(operations.overview(user.tenantId()));
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<Map<String, Object>>> deliveries(@RequestParam(defaultValue = "") String status) {
        return ApiResponse.ok(operations.deliveries(user.tenantId(), status));
    }

    @PostMapping("/deliveries/{id}/replay")
    public ApiResponse<Map<String, Object>> replay(@PathVariable UUID id) {
        Map<String, Object> result = operations.replay(user.tenantId(), id);
        record("channel_replay", String.valueOf(id));
        return ApiResponse.ok(result);
    }

    @GetMapping("/conversations")
    public ApiResponse<List<Map<String, Object>>> conversations() {
        return ApiResponse.ok(operations.conversations(user.tenantId()));
    }

    @GetMapping("/routes")
    public ApiResponse<List<Map<String, Object>>> routes() {
        return ApiResponse.ok(operations.routes(user.tenantId()));
    }

    @PostMapping("/routes")
    public ApiResponse<Map<String, Object>> saveRoute(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = operations.saveRoute(user.tenantId(), body);
        record("channel_route", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/card-templates")
    public ApiResponse<Map<String, Object>> cardTemplates(
            @RequestParam(defaultValue = "AgentHub") String title,
            @RequestParam(defaultValue = "Channel message preview") String text) {
        return ApiResponse.ok(operations.cardTemplates(title, text));
    }

    @PostMapping("/outbound")
    public ApiResponse<Map<String, Object>> outbound(@RequestBody Map<String, Object> body) {
        String channel = String.valueOf(body.getOrDefault("channel", ""));
        String recipientId = String.valueOf(body.getOrDefault("recipientId", ""));
        String externalId = String.valueOf(body.getOrDefault("externalMessageId", "manual:" + UUID.randomUUID()));
        long agentId = body.get("agentId") instanceof Number value ? value.longValue() : 1L;
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = body.get("payload") instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
        Map<String, Object> result = operations.enqueueAndDeliver(user.tenantId(), channel, externalId,
                String.valueOf(body.getOrDefault("conversationKey", recipientId)), recipientId, agentId, payload, null);
        record("channel_outbound", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/simulate-inbound")
    public ApiResponse<Map<String, Object>> simulateInbound(@RequestBody Map<String, Object> body) {
        String channel = String.valueOf(body.getOrDefault("channel", "dingtalk"));
        String sender = String.valueOf(body.getOrDefault("senderId", "local-test-user"));
        String conversation = String.valueOf(body.getOrDefault("conversationKey", "local-test-conversation"));
        String externalId = String.valueOf(body.getOrDefault("externalMessageId", "simulation:" + UUID.randomUUID()));
        String text = String.valueOf(body.getOrDefault("text", "hello"));
        Map<String, Object> result = operations.handleInbound(new ChannelOperationsService.InboundMessage(
                user.tenantId(), channel, externalId, conversation, sender,
                String.valueOf(body.getOrDefault("chatType", "direct")), text, body));
        record("channel_inbound_simulation", String.valueOf(result.get("deliveryId")));
        return ApiResponse.ok(result);
    }

    private void record(String type, String detail) {
        audit.record(type, user.userId(), user.require().username(), type, detail, "success", user.tenantId());
    }
}
