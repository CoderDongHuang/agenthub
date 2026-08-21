package com.agenthub.channel;

import com.agenthub.common.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/internal/channel")
public class DingTalkStreamController {
    private final ChannelOperationsService operations;

    public DingTalkStreamController(ChannelOperationsService operations) {
        this.operations = operations;
    }

    @PostMapping("/dingtalk-stream")
    public ApiResponse<Map<String, Object>> inbound(@RequestBody Map<String, Object> body) {
        long tenantId = operations.resolveTenant("dingtalk", text(body.get("robotCode")));
        String sender = first(body, "senderStaffId", "senderId");
        String externalId = first(body, "msgId", "messageId");
        if (externalId.isBlank()) externalId = UUID.randomUUID().toString();
        String conversation = first(body, "conversationId", "senderId");
        String content = body.get("text") instanceof Map<?, ?> text ? text(text.get("content")) : "";
        return ApiResponse.ok(operations.handleInbound(new ChannelOperationsService.InboundMessage(
                tenantId, "dingtalk", externalId, conversation, sender,
                text(body.getOrDefault("conversationType", "direct")), content,
                text(body.get("sessionWebhook")), body)));
    }

    private static String first(Map<String, Object> body, String... names) {
        for (String name : names) {
            String value = text(body.get(name));
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
