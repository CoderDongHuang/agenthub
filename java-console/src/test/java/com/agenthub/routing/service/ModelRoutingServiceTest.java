package com.agenthub.routing.service;

import com.agenthub.platform.service.WebhookUrlValidator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ModelRoutingServiceTest {

    @Test
    void prefersQualityLowLatencyAndRequestedModel() {
        ModelRoutingService service = new ModelRoutingService(
                mock(JdbcTemplate.class), mock(WebhookUrlValidator.class));
        double preferred = service.score(90, 0.02, 500, true, true);
        double degraded = service.score(70, 0.08, 4000, false, false);
        assertTrue(preferred > degraded);
    }

    @Test
    void costAndLatencyAffectScore() {
        ModelRoutingService service = new ModelRoutingService(
                mock(JdbcTemplate.class), mock(WebhookUrlValidator.class));
        assertTrue(service.score(85, 0.01, 300, false, false)
                > service.score(85, 8, 5000, false, false));
    }
}
