package com.agenthub.diagnostics.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationDiagnosticsServiceTest {

    @Test
    void acceptsRuntimeHealthStatusUsedByPythonEngine() {
        assertThat(ConfigurationDiagnosticsService.isHealthyResponse(Map.of("status", "UP"))).isTrue();
        assertThat(ConfigurationDiagnosticsService.isHealthyResponse(Map.of("status", "healthy"))).isTrue();
        assertThat(ConfigurationDiagnosticsService.isHealthyResponse(Map.of("status", "ok"))).isTrue();
        assertThat(ConfigurationDiagnosticsService.isHealthyResponse(Map.of("status", "down"))).isFalse();
    }
}
