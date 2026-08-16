package com.agenthub.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    @Test
    void workflowWebhookSecretHeaderIsAllowedByCors() {
        SecurityConfig securityConfig = new SecurityConfig(null, null, "http://127.0.0.1:5173");
        MockHttpServletRequest request = new MockHttpServletRequest(
                "OPTIONS", "/api/hooks/workflows/1");

        CorsConfiguration cors = securityConfig.corsConfigurationSource().getCorsConfiguration(request);

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedHeaders()).contains("X-Workflow-Secret");
    }
}
