package com.agenthub.agent.controller;

import com.agenthub.agent.dto.AgentCreateRequest;
import com.agenthub.agent.entity.AgentDefinition;
import com.agenthub.agent.service.AgentService;
import com.agenthub.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping
    public ApiResponse<AgentDefinition> create(@Valid @RequestBody AgentCreateRequest request) {
        // TODO: 从 JWT 获取当前用户 ID
        AgentDefinition agent = agentService.create(request, 1L);
        return ApiResponse.ok(agent);
    }

    @GetMapping
    public ApiResponse<Page<AgentDefinition>> list(
            @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(agentService.list(pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentDefinition> get(@PathVariable Long id) {
        return ApiResponse.ok(agentService.get(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentDefinition> update(@PathVariable Long id, @RequestBody AgentCreateRequest request) {
        return ApiResponse.ok(agentService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<String> delete(@PathVariable Long id) {
        agentService.delete(id);
        return ApiResponse.ok("Agent 已删除");
    }

    @PutMapping("/{id}/publish")
    public ApiResponse<AgentDefinition> publish(@PathVariable Long id) {
        return ApiResponse.ok(agentService.publish(id));
    }

    @PutMapping("/{id}/disable")
    public ApiResponse<AgentDefinition> disable(@PathVariable Long id) {
        return ApiResponse.ok(agentService.disable(id));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(agentService.stats());
    }
}
