package com.agenthub.agent.controller;

import com.agenthub.agent.entity.AgentDefinition;
import com.agenthub.agent.service.AgentService;
import com.agenthub.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/agents")
public class InternalAgentController {

    private final AgentService agentService;

    public InternalAgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> getRuntimeConfig(@PathVariable Long id) {
        AgentDefinition agent = agentService.get(id);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", agent.getId());
        config.put("name", agent.getName());
        config.put("systemPrompt", agent.getSystemPrompt());
        config.put("model", agent.getModel());
        config.put("temperature", agent.getTemperature());
        config.put("maxTokens", agent.getMaxTokens());
        config.put("status", agent.getStatus());
        return ApiResponse.ok(config);
    }
}
