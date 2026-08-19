package com.agenthub.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ObservabilityQualityServiceTest {

    @Test
    void comparesLatestBaselineAndCandidateScores() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", 1L, "agent_version_id", 10L, "status", "passed", "score", new BigDecimal("82.00"))),
                        List.of(Map.of("id", 2L, "agent_version_id", 11L, "status", "passed", "score", new BigDecimal("87.50"))));

        Map<String, Object> result = new EvaluationService(jdbc, new ObjectMapper(), mock(AgentVersionService.class))
                .compareVersions(3L, 4L, 10L, 11L);

        assertEquals(new BigDecimal("5.50"), result.get("scoreDelta"));
        assertEquals(Boolean.TRUE, result.get("candidateImproved"));
        assertEquals(Boolean.TRUE, result.get("candidatePassed"));
    }

    @Test
    void canaryGuardRollsBackWhenCandidateFallsBelowThreshold() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Map<String, Object> version = Map.of("id", 10L, "agent_id", 9L, "version_no", 1,
                "status", "canary", "rollout_percent", 10, "evaluation_status", "passed",
                "config", "{}");
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(version), List.of(version), List.of(version));
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn("{}");
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new BigDecimal("90")), List.of(new BigDecimal("70")));

        Map<String, Object> result = new AgentVersionService(jdbc, new ObjectMapper())
                .guardCanary(3L, 9L, 10L, 10L, new BigDecimal("-5"));

        assertEquals(Boolean.TRUE, result.get("rollbackTriggered"));
        assertEquals(new BigDecimal("-20"), result.get("scoreDelta"));
        verify(jdbc).update(contains("UPDATE agent_version SET status='archived'"), any(Object[].class));
    }
}
