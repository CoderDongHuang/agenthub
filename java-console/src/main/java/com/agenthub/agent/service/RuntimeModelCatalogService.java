package com.agenthub.agent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class RuntimeModelCatalogService {
    private final String runtimeUrl;
    private final String internalToken;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public RuntimeModelCatalogService(
            @Value("${python.runtime.base-url:http://localhost:8000}") String runtimeUrl,
            @Value("${agenthub.internal-token}") String internalToken,
            ObjectMapper mapper) {
        this.runtimeUrl = runtimeUrl.replaceAll("/+$", "");
        this.internalToken = internalToken;
        this.mapper = mapper;
    }

    public void assertPublishable(String model) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(runtimeUrl + "/runtime/capabilities"))
                    .timeout(Duration.ofSeconds(10)).header("X-Internal-Token", internalToken)
                    .header("X-Tenant-Id", "0").GET().build();
            HttpResponse<String> raw = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (raw.statusCode() != 200) throw new IllegalStateException("Runtime model catalog returned HTTP " + raw.statusCode());
            Map<String, Object> response = mapper.readValue(raw.body(), new TypeReference<>() {});
            Map<String, Object> models = asMap(response == null ? null : response.get("models"));
            Object rawCatalog = models.get("models");
            if (!(rawCatalog instanceof List<?> catalog)) throw new IllegalStateException("Runtime model catalog is unavailable");
            for (Object item : catalog) {
                Map<String, Object> entry = asMap(item);
                if (!model.equals(String.valueOf(entry.get("id")))) continue;
                if (!Boolean.TRUE.equals(entry.get("configured"))) {
                    throw new IllegalStateException("Model provider is not configured: " + model);
                }
                return;
            }
            throw new IllegalArgumentException("Unsupported runtime model: " + model);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify the runtime model catalog", exception);
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }
}
