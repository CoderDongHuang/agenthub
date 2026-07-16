package com.agenthub.agent.service;

import com.agenthub.agent.dto.AgentCreateRequest;
import com.agenthub.agent.entity.AgentDefinition;
import com.agenthub.agent.repository.AgentDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AgentService {

    private final AgentDefinitionRepository agentRepository;

    public AgentService(AgentDefinitionRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    public AgentDefinition create(AgentCreateRequest request, Long userId) {
        AgentDefinition agent = AgentDefinition.builder()
                .name(request.getName())
                .description(request.getDescription())
                .systemPrompt(request.getSystemPrompt())
                .model(request.getModel() != null ? request.getModel() : "gpt-4o")
                .temperature(request.getTemperature() != null ? request.getTemperature() : java.math.BigDecimal.valueOf(0.7))
                .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 4096)
                .icon(request.getIcon())
                .status("draft")
                .createdBy(userId)
                .tenantId(0L)
                .build();
        return agentRepository.save(agent);
    }

    public Page<AgentDefinition> list(Pageable pageable) {
        return agentRepository.findByTenantIdOrderByUpdatedAtDesc(0L, pageable);
    }

    public AgentDefinition get(Long id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Agent 不存在: " + id));
    }

    public AgentDefinition update(Long id, AgentCreateRequest request) {
        AgentDefinition agent = get(id);
        if (request.getName() != null) agent.setName(request.getName());
        if (request.getDescription() != null) agent.setDescription(request.getDescription());
        if (request.getSystemPrompt() != null) agent.setSystemPrompt(request.getSystemPrompt());
        if (request.getModel() != null) agent.setModel(request.getModel());
        if (request.getTemperature() != null) agent.setTemperature(request.getTemperature());
        if (request.getMaxTokens() != null) agent.setMaxTokens(request.getMaxTokens());
        if (request.getIcon() != null) agent.setIcon(request.getIcon());
        return agentRepository.save(agent);
    }

    public AgentDefinition publish(Long id) {
        AgentDefinition agent = get(id);
        agent.setStatus("published");
        agent.setPublishedAt(LocalDateTime.now());
        return agentRepository.save(agent);
    }

    public AgentDefinition disable(Long id) {
        AgentDefinition agent = get(id);
        agent.setStatus("disabled");
        return agentRepository.save(agent);
    }

    public void delete(Long id) {
        AgentDefinition agent = get(id);
        agentRepository.delete(agent);
    }

    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("total", agentRepository.countByTenantId(0L));
        stats.put("published", agentRepository.countByStatus("published"));
        stats.put("draft", agentRepository.countByStatus("draft"));
        stats.put("disabled", agentRepository.countByStatus("disabled"));
        return stats;
    }
}
