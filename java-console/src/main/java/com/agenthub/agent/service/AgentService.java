package com.agenthub.agent.service;

import com.agenthub.agent.dto.AgentCreateRequest;
import com.agenthub.agent.entity.AgentDefinition;
import com.agenthub.agent.repository.AgentDefinitionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import com.agenthub.common.config.TenantContext;

import java.time.LocalDateTime;
import com.agenthub.release.service.AgentVersionService;

@Service
public class AgentService {

    private final AgentDefinitionRepository agentRepository;
    private final AgentVersionService versionService;

    public AgentService(AgentDefinitionRepository agentRepository, AgentVersionService versionService) {
        this.agentRepository = agentRepository;
        this.versionService = versionService;
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
                .tenantId(requireTenant())
                .build();
        AgentDefinition saved = agentRepository.save(agent);
        if (saved.getId() != null) {
            versionService.snapshot(saved.getTenantId(), userId, saved.getId(), "Initial Agent draft");
        }
        return saved;
    }

    public Page<AgentDefinition> list(Pageable pageable) {
        return agentRepository.findByTenantIdOrderByUpdatedAtDesc(requireTenant(), pageable);
    }

    public AgentDefinition get(Long id) {
        return agentRepository.findByIdAndTenantId(id, requireTenant())
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
        AgentDefinition saved = agentRepository.save(agent);
        versionService.snapshot(requireTenant(), saved.getCreatedBy(), saved.getId(), "Agent definition updated");
        return saved;
    }

    public AgentDefinition publish(Long id) {
        AgentDefinition agent = get(id);
        java.util.Map<String, Object> version = versionService.snapshot(requireTenant(), agent.getCreatedBy(), id,
                "Release candidate");
        Long versionId = ((Number) version.get("id")).longValue();
        versionService.release(requireTenant(), id, versionId, 100);
        agent.setStatus("published");
        agent.setPublishedAt(LocalDateTime.now());
        agent.setCurrentVersionId(versionId);
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
        Long tenantId = requireTenant();
        stats.put("total", agentRepository.countByTenantId(tenantId));
        stats.put("published", agentRepository.countByTenantIdAndStatus(tenantId, "published"));
        stats.put("draft", agentRepository.countByTenantIdAndStatus(tenantId, "draft"));
        stats.put("disabled", agentRepository.countByTenantIdAndStatus(tenantId, "disabled"));
        return stats;
    }

    private Long requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new IllegalStateException("Tenant context is required");
        return tenantId;
    }
}
