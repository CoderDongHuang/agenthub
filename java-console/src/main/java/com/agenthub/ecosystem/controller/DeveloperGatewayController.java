package com.agenthub.ecosystem.controller;

import com.agenthub.audit.service.AuditService;
import com.agenthub.common.response.ApiResponse;
import com.agenthub.ecosystem.service.PlatformEcosystemService;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/gateway")
public class DeveloperGatewayController {
    private final PlatformEcosystemService ecosystem;
    private final AuditService audit;

    public DeveloperGatewayController(PlatformEcosystemService ecosystem, AuditService audit) {
        this.ecosystem = ecosystem;
        this.audit = audit;
    }

    @PostMapping(value = "/{apiVersion}/invoke", consumes = "application/json")
    public ApiResponse<Map<String, Object>> invoke(
            @PathVariable String apiVersion,
            @RequestHeader("X-Developer-Key") String publicKey,
            @RequestHeader("X-Timestamp") long timestamp,
            @RequestHeader("X-Nonce") String nonce,
            @RequestHeader("X-Signature") String signature,
            @RequestBody String rawBody) {
        Map<String, Object> result = ecosystem.invokeGateway(apiVersion, publicKey, timestamp, nonce, signature,
                rawBody.getBytes(StandardCharsets.UTF_8));
        long tenantId = ((Number) result.get("tenantId")).longValue();
        audit.record("developer_gateway", null, "developer-app", "Invoke " + result.get("operation"),
                String.valueOf(result.get("requestId")), "success", tenantId);
        return ApiResponse.ok(result);
    }
}
