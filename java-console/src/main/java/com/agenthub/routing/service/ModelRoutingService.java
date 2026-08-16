package com.agenthub.routing.service;

import com.agenthub.platform.service.WebhookUrlValidator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Service
public class ModelRoutingService {

    private static final int FAILURE_THRESHOLD = 3;
    private static final int RECOVERY_SECONDS = 60;

    private final JdbcTemplate jdbc;
    private final WebhookUrlValidator urlValidator;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();

    public ModelRoutingService(JdbcTemplate jdbc, WebhookUrlValidator urlValidator) {
        this.jdbc = jdbc;
        this.urlValidator = urlValidator;
    }

    public RouteDecision route(Long tenantId, String preferredModel, Map<String, Object> constraints) {
        String region = text(constraints.getOrDefault("region", "global"));
        double maxCost = number(constraints.get("maxCostPer1k"), Double.MAX_VALUE);
        double minQuality = number(constraints.get("minQuality"), 0);
        long maxLatency = (long) number(constraints.get("maxLatencyMs"), Long.MAX_VALUE);
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT id,model,provider,region,cost_per_1k,quality_score,latency_slo_ms,status,circuit_state," +
                        "consecutive_failures,last_latency_ms,recover_after FROM model_endpoint WHERE tenant_id=? " +
                        "AND (circuit_state='closed' OR (circuit_state='open' AND recover_after<=NOW()))",
                tenantId);
        List<ScoredEndpoint> accepted = new ArrayList<>();
        for (Map<String, Object> candidate : candidates) {
            double cost = number(candidate.get("cost_per_1k"), 0);
            double quality = number(candidate.get("quality_score"), 0);
            long latency = ((Number) Objects.requireNonNullElse(candidate.get("last_latency_ms"),
                    candidate.get("latency_slo_ms"))).longValue();
            String candidateRegion = text(candidate.get("region"));
            if (cost > maxCost || quality < minQuality || latency > maxLatency) continue;
            if (!"global".equals(region) && !region.equals(candidateRegion) && !"global".equals(candidateRegion)) continue;
            double score = score(quality, cost, latency,
                    Objects.equals(preferredModel, candidate.get("model")), Objects.equals(region, candidateRegion));
            accepted.add(new ScoredEndpoint(candidate, score));
        }
        accepted.sort(Comparator.comparingDouble(ScoredEndpoint::score).reversed());
        if (accepted.isEmpty()) {
            return new RouteDecision(preferredModel, null, "No managed healthy alternative; using Agent model", 0,
                    "unmanaged");
        }
        ScoredEndpoint selected = accepted.get(0);
        Map<String, Object> endpoint = selected.endpoint();
        if ("open".equals(endpoint.get("circuit_state"))) {
            jdbc.update("UPDATE model_endpoint SET circuit_state='half_open',updated_at=NOW() WHERE id=?",
                    endpoint.get("id"));
        }
        String model = text(endpoint.get("model"));
        String reason = Objects.equals(model, preferredModel)
                ? "Preferred model satisfies cost, latency, quality and health constraints"
                : "Automatic failover/routing selected the highest scoring healthy endpoint";
        return new RouteDecision(model, ((Number) endpoint.get("id")).longValue(), reason,
                Math.round(selected.score() * 100.0) / 100.0, text(endpoint.get("provider")));
    }

    public List<Map<String, Object>> list(Long tenantId) {
        return jdbc.queryForList(
                "SELECT id,model,provider,region,base_url,cost_per_1k,quality_score,latency_slo_ms,status," +
                        "circuit_state,consecutive_failures,last_latency_ms,last_error,last_checked_at,recover_after,updated_at " +
                        "FROM model_endpoint WHERE tenant_id=? ORDER BY provider,model,region", tenantId);
    }

    public Map<String, Object> upsert(Long tenantId, Map<String, Object> body) {
        String model = required(body, "model");
        String provider = required(body, "provider");
        String region = text(body.getOrDefault("region", "global"));
        String baseUrl = text(body.get("baseUrl"));
        if (!baseUrl.isBlank()) urlValidator.validate(baseUrl);
        jdbc.update("INSERT INTO model_endpoint(tenant_id,model,provider,region,base_url,cost_per_1k,quality_score," +
                        "latency_slo_ms,status) VALUES (?,?,?,?,?,?,?,?, 'unknown') ON CONFLICT(tenant_id,model,region) " +
                        "DO UPDATE SET provider=EXCLUDED.provider,base_url=EXCLUDED.base_url,cost_per_1k=EXCLUDED.cost_per_1k," +
                        "quality_score=EXCLUDED.quality_score,latency_slo_ms=EXCLUDED.latency_slo_ms,updated_at=NOW()",
                tenantId, model, provider, region, baseUrl.isBlank() ? null : baseUrl,
                number(body.get("costPer1k"), 0), number(body.get("qualityScore"), 80),
                (int) number(body.get("latencySloMs"), 5000));
        return jdbc.queryForMap("SELECT * FROM model_endpoint WHERE tenant_id=? AND model=? AND region=?",
                tenantId, model, region);
    }

    public Map<String, Object> report(Long tenantId, Long endpointId, boolean success, long latencyMs, String error) {
        Map<String, Object> endpoint = requireEndpoint(tenantId, endpointId);
        int failures = success ? 0 : ((Number) endpoint.get("consecutive_failures")).intValue() + 1;
        int slo = ((Number) endpoint.get("latency_slo_ms")).intValue();
        boolean effectiveSuccess = success && latencyMs <= slo;
        if (!effectiveSuccess && success) {
            failures = ((Number) endpoint.get("consecutive_failures")).intValue() + 1;
            error = "Latency SLO exceeded";
        }
        String circuit = effectiveSuccess ? "closed" : failures >= FAILURE_THRESHOLD ? "open" :
                text(endpoint.get("circuit_state"));
        String status = effectiveSuccess ? "healthy" : failures >= FAILURE_THRESHOLD ? "unhealthy" : "degraded";
        jdbc.update("UPDATE model_endpoint SET status=?,circuit_state=?,consecutive_failures=?,last_latency_ms=?," +
                        "last_error=?,last_checked_at=NOW(),recover_after=CASE WHEN ?='open' THEN NOW()+(?*INTERVAL '1 second') " +
                        "ELSE NULL END,updated_at=NOW() WHERE id=? AND tenant_id=?",
                status, circuit, failures, Math.max(0, latencyMs), effectiveSuccess ? null : safeError(error),
                circuit, RECOVERY_SECONDS, endpointId, tenantId);
        return requireEndpoint(tenantId, endpointId);
    }

    public Map<String, Object> probe(Long tenantId, Long endpointId) {
        Map<String, Object> endpoint = requireEndpoint(tenantId, endpointId);
        String baseUrl = text(endpoint.get("base_url"));
        if (baseUrl.isBlank()) throw new IllegalStateException("Endpoint baseUrl is not configured");
        URI uri = urlValidator.validate(baseUrl);
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            boolean reachable = response.statusCode() < 500;
            return report(tenantId, endpointId, reachable, latency,
                    reachable ? null : "HTTP " + response.statusCode());
        } catch (Exception exception) {
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return report(tenantId, endpointId, false, latency, exception.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${agenthub.routing.probe-interval-ms:60000}", initialDelay = 30000)
    public void probeConfiguredEndpoints() {
        List<Map<String, Object>> endpoints = jdbc.queryForList(
                "SELECT id,tenant_id FROM model_endpoint WHERE base_url IS NOT NULL AND base_url<>''");
        for (Map<String, Object> endpoint : endpoints) {
            try {
                probe(((Number) endpoint.get("tenant_id")).longValue(), ((Number) endpoint.get("id")).longValue());
            } catch (Exception ignored) {
                // probe() already persists operational failures when the request starts
            }
        }
    }

    double score(double quality, double costPer1k, long latencyMs, boolean preferred, boolean sameRegion) {
        double costScore = 100.0 / (1.0 + Math.max(0, costPer1k));
        double latencyScore = 100.0 / (1.0 + Math.max(0, latencyMs) / 1000.0);
        return quality * 0.5 + latencyScore * 0.3 + costScore * 0.2 +
                (preferred ? 12 : 0) + (sameRegion ? 4 : 0);
    }

    private Map<String, Object> requireEndpoint(Long tenantId, Long endpointId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM model_endpoint WHERE id=? AND tenant_id=?", endpointId, tenantId);
        if (rows.isEmpty()) throw new NoSuchElementException("Model endpoint not found");
        return rows.get(0);
    }

    private String required(Map<String, Object> body, String key) {
        String value = text(body.get(key));
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }
    private String safeError(String error) {
        if (error == null) return "Health check failed";
        return error.length() <= 500 ? error : error.substring(0, 500);
    }

    private record ScoredEndpoint(Map<String, Object> endpoint, double score) {}
    public record RouteDecision(String model, Long endpointId, String reason, double score, String provider) {}
}
