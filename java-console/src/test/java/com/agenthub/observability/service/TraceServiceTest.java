package com.agenthub.observability.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TraceServiceTest {

    @Test
    void replayRedactsPromptSpanAndSecretsWithoutExecutingTools() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TraceService service = spy(new TraceService(jdbc, new ObjectMapper()));
        UUID traceId = UUID.randomUUID();
        doReturn(Map.of("model", "qwen", "spans", List.of(Map.of(
                "input", Map.of("prompt", "email ops@example.com phone 13800138000"),
                "output", Map.of("authorization", "Bearer abcdefghijk", "text", "safe"),
                "metadata", Map.of("api_key", "sk-test-secret"))))).when(service).get(7L, traceId);

        Map<String, Object> result = service.replay(7L, traceId);

        assertEquals("redacted_replay", result.get("mode"));
        assertEquals(Boolean.TRUE, result.get("replayable"));
        String payload = new ObjectMapper().writeValueAsString(result.get("trace"));
        assertFalse(payload.contains("ops@example.com"));
        assertFalse(payload.contains("13800138000"));
        assertFalse(payload.contains("abcdefghijk"));
        assertFalse(payload.contains("sk-test-secret"));
        verify(service).get(7L, traceId);
        verifyNoInteractions(jdbc);
    }

    @Test
    void observabilityReturnsZeroRatesForAnEmptyWindow() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("traces", 0L, "failed", 0L, "provider_errors", 0L,
                        "latency_p95_ms", 0L, "retries", 0L, "cancellations", 0L,
                        "queue_depth", 0L, "tokens", 0L, "cost", 0));
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        Map<String, Object> result = new TraceService(jdbc, new ObjectMapper()).observability(7L, 999);

        assertEquals(168, result.get("windowHours"));
        assertEquals(0.0, result.get("errorRatePercent"));
        assertEquals(0.0, result.get("providerErrorRatePercent"));
        assertEquals(100.0, result.get("toolSuccessRatePercent"));
        assertEquals(List.of(), result.get("anomalies"));
    }
}
