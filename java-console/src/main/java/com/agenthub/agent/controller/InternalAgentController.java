package com.agenthub.agent.controller;

import com.agenthub.agent.entity.AgentDefinition;
import com.agenthub.agent.service.AgentService;
import com.agenthub.common.response.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/agents")
public class InternalAgentController {

    private final AgentService agentService;
    private final String internalToken;

    public InternalAgentController(
            AgentService agentService,
            @Value("${agenthub.internal-token:agenthub-local-runtime}") String internalToken) {
        this.agentService = agentService;
        this.internalToken = internalToken;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRuntimeConfig(
            @PathVariable Long id,
            @RequestHeader(value = "X-Internal-Token", defaultValue = "") String providedToken) {
        if (!tokenMatches(providedToken)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(403, "Invalid internal token"));
        }

        AgentDefinition agent = agentService.get(id);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", agent.getId());
        config.put("name", agent.getName());
        config.put("systemPrompt", agent.getSystemPrompt());
        config.put("model", agent.getModel());
        config.put("temperature", agent.getTemperature());
        config.put("maxTokens", agent.getMaxTokens());
        config.put("status", agent.getStatus());
        return ResponseEntity.ok(ApiResponse.ok(config));
    }

    private boolean tokenMatches(String providedToken) {
        return MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8)
        );
    }
}
