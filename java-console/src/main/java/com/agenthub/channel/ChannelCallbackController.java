package com.agenthub.channel;

import com.agenthub.common.response.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agenthub.grpc.PythonAgentClient;
import com.agenthub.grpc.stub.ExecutionRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/channel")
public class ChannelCallbackController {
    private final ObjectMapper mapper;
    private final PythonAgentClient runtime;
    private final String defaultAgentId;

    public ChannelCallbackController(ObjectMapper mapper, PythonAgentClient runtime,
            @Value("${agenthub.channel.default-agent-id:1}") String defaultAgentId) {
        this.mapper = mapper;
        this.runtime = runtime;
        this.defaultAgentId = defaultAgentId;
    }

    @GetMapping("/feishu/callback")
    public Map<String, String> feishuVerify(@RequestParam(required = false) String challenge) {
        return Map.of("challenge", challenge == null ? "" : challenge);
    }

    @PostMapping(value = "/feishu/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> feishu(@RequestBody String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        if (root.has("challenge")) return Map.of("challenge", root.path("challenge").asText());
        String text = root.at("/event/message/content").asText("");
        String user = root.at("/event/sender/sender_id/open_id").asText("feishu-user");
        return Map.of("status", dispatch("feishu", user, text));
    }

    @GetMapping("/wechat/callback")
    public String wechatVerify(@RequestParam(required = false) String echostr) {
        return echostr == null ? "" : echostr;
    }

    @PostMapping(value = "/wechat/callback", consumes = {MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE})
    public String wechat(@RequestBody String body) throws Exception {
        String user = xml(body, "FromUserName", "wechat-user");
        String text = xml(body, "Content", "");
        dispatch("wechat", user, text);
        return "success";
    }

    @PostMapping(value = "/dingtalk/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, String>> dingtalk(@RequestBody String body) throws Exception {
        JsonNode root = mapper.readTree(body);
        String user = root.path("senderStaffId").asText(root.path("senderId").asText("dingtalk-user"));
        String text = root.at("/text/content").asText(root.path("content").asText(""));
        return ApiResponse.ok(Map.of("status", dispatch("dingtalk", user, text)));
    }

    private String dispatch(String channel, String user, String message) {
        if (message == null || message.isBlank()) return "ignored";
        AtomicReference<String> result = new AtomicReference<>("accepted");
        ExecutionRequest request = ExecutionRequest.newBuilder().setSessionId(channel + "-" + user + "-" + UUID.randomUUID())
                .setAgentId(defaultAgentId).setUserId(user).setTenantId("0").setMessage(message).setChannel(channel).build();
        runtime.executeAgent(request, response -> {}, error -> result.set("failed"), () -> {});
        return result.get();
    }

    private String xml(String body, String tag, String fallback) {
        String open = "<" + tag + ">", close = "</" + tag + ">";
        int start = body.indexOf(open), end = body.indexOf(close);
        return start >= 0 && end > start ? body.substring(start + open.length(), end) : fallback;
    }
}
