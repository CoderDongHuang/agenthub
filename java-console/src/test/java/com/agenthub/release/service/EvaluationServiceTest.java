package com.agenthub.release.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class EvaluationServiceTest {

    private EvaluationService service;

    @BeforeEach
    void setUp() {
        service = new EvaluationService(mock(JdbcTemplate.class), new ObjectMapper(), mock(AgentVersionService.class));
    }

    @Test
    void evaluatesTextAssertions() {
        assertTrue(service.evaluate("contains", Map.of("value", "approved"), "request approved").passed());
        assertTrue(service.evaluate("not_contains", Map.of("value", "secret"), "safe response").passed());
        assertTrue(service.evaluate("regex", Map.of("value", "order-[0-9]+"), "order-42").passed());
        assertFalse(service.evaluate("exact", Map.of("value", "yes"), "no").passed());
    }

    @Test
    void evaluatesToolJsonAndRagCitation() {
        assertTrue(service.evaluate("json_contains", Map.of("value", Map.of("status", "ok")),
                Map.of("status", "ok", "id", 7)).passed());
        assertTrue(service.evaluate("citation", Map.of("documentId", 12),
                Map.of("citations", new Object[]{Map.of("documentId", 12)})).passed());
        assertFalse(service.evaluate("json_contains", Map.of("value", Map.of("status", "ok")),
                "not-json").passed());
    }
}
