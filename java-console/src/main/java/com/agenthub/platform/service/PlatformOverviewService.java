package com.agenthub.platform.service;

import com.agenthub.agent.repository.AgentDefinitionRepository;
import com.agenthub.approval.repository.ApprovalRequestRepository;
import com.agenthub.audit.repository.AuditLogRepository;
import com.agenthub.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PlatformOverviewService {

    private final AgentDefinitionRepository agentRepository;
    private final ApprovalRequestRepository approvalRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final RestClient runtimeClient;

    public PlatformOverviewService(
            AgentDefinitionRepository agentRepository,
            ApprovalRequestRepository approvalRepository,
            AuditLogRepository auditLogRepository,
            UserRepository userRepository,
            JdbcTemplate jdbcTemplate,
            @Value("${python.runtime.base-url:http://localhost:8000}") String runtimeBaseUrl) {
        this.agentRepository = agentRepository;
        this.approvalRepository = approvalRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeClient = RestClient.builder().baseUrl(runtimeBaseUrl).build();
    }

    public Map<String, Object> getOverview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("agents", agentMetrics());
        overview.put("governance", governanceMetrics());
        overview.put("usage", usageMetrics());
        overview.put("runtime", runtimeSnapshot());
        overview.put("recentActivity", auditLogRepository
                .findAllByOrderByCreatedAtDesc(Pageable.ofSize(8))
                .getContent());
        return overview;
    }

    private Map<String, Object> agentMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("total", agentRepository.countByTenantId(0L));
        metrics.put("published", agentRepository.countByStatus("published"));
        metrics.put("draft", agentRepository.countByStatus("draft"));
        metrics.put("disabled", agentRepository.countByStatus("disabled"));
        return metrics;
    }

    private Map<String, Object> governanceMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("pendingApprovals", approvalRepository.countByStatus("pending"));
        metrics.put("auditEvents", auditLogRepository.count());
        metrics.put("users", userRepository.count());
        metrics.put("tools", queryLong("SELECT COUNT(*) FROM tool_definition"));
        metrics.put("highRiskTools", queryLong("SELECT COUNT(*) FROM tool_definition WHERE risk_level = 'high'"));
        return metrics;
    }

    private Map<String, Object> usageMetrics() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Map<String, Object> monthly = jdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(input_tokens), 0) AS input_tokens, " +
                "COALESCE(SUM(output_tokens), 0) AS output_tokens, " +
                "COALESCE(SUM(cost), 0) AS cost " +
                "FROM token_usage WHERE created_at >= date_trunc('month', NOW())"
        );
        metrics.put("monthlyInputTokens", monthly.get("input_tokens"));
        metrics.put("monthlyOutputTokens", monthly.get("output_tokens"));
        metrics.put("monthlyCost", monthly.getOrDefault("cost", BigDecimal.ZERO));
        metrics.put("last7Days", jdbcTemplate.queryForList(
                "SELECT TO_CHAR(created_at::date, 'MM-DD') AS day, " +
                "SUM(input_tokens + output_tokens) AS tokens, SUM(cost) AS cost " +
                "FROM token_usage WHERE created_at >= NOW() - INTERVAL '7 days' " +
                "GROUP BY created_at::date ORDER BY created_at::date"
        ));
        return metrics;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runtimeSnapshot() {
        try {
            Map<String, Object> snapshot = runtimeClient.get()
                    .uri("/runtime/capabilities")
                    .retrieve()
                    .body(Map.class);
            return snapshot == null ? Map.of("status", "UNKNOWN") : snapshot;
        } catch (Exception exception) {
            return Map.of(
                    "status", "DOWN",
                    "service", "python-runtime",
                    "message", "Python runtime is not reachable"
            );
        }
    }

    private long queryLong(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
