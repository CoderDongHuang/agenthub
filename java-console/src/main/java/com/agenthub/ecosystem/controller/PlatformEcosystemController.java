package com.agenthub.ecosystem.controller;

import com.agenthub.audit.service.AuditService;
import com.agenthub.common.response.ApiResponse;
import com.agenthub.ecosystem.service.PlatformEcosystemService;
import com.agenthub.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ecosystem")
public class PlatformEcosystemController {
    private final PlatformEcosystemService ecosystem;
    private final CurrentUser user;
    private final AuditService audit;

    public PlatformEcosystemController(PlatformEcosystemService ecosystem, CurrentUser user, AuditService audit) {
        this.ecosystem = ecosystem;
        this.user = user;
        this.audit = audit;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(ecosystem.overview(user.tenantId()));
    }

    @GetMapping("/packages")
    public ApiResponse<List<Map<String, Object>>> packages() {
        return ApiResponse.ok(ecosystem.packages(user.tenantId()));
    }

    @PostMapping("/packages")
    public ApiResponse<Map<String, Object>> registerPackage(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = ecosystem.registerPackage(user.tenantId(), user.userId(), body);
        record("ecosystem_package", "Register signed package", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/packages/{id}/verify")
    public ApiResponse<Map<String, Object>> verifyPackage(@PathVariable long id) {
        Map<String, Object> result = ecosystem.verifyPackage(user.tenantId(), id);
        record("ecosystem_package", "Verify package signature", String.valueOf(id));
        return ApiResponse.ok(result);
    }

    @GetMapping("/packages/{id}/artifact")
    public ApiResponse<Map<String, Object>> packageArtifact(@PathVariable long id) {
        Map<String, Object> result = ecosystem.packageArtifact(user.tenantId(), id);
        record("ecosystem_package", "Download private package artifact", String.valueOf(id));
        return ApiResponse.ok(result);
    }

    @PostMapping("/packages/{id}/scan")
    public ApiResponse<Map<String, Object>> scanPackage(@PathVariable long id) {
        Map<String, Object> result = ecosystem.scanPackage(user.tenantId(), id);
        record("ecosystem_supply_chain", "Scan package supply chain", String.valueOf(id));
        return ApiResponse.ok(result);
    }

    @PostMapping("/sandbox/evaluate")
    public ApiResponse<Map<String, Object>> sandbox(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(ecosystem.sandboxProfile(body));
    }

    @GetMapping("/mcp/connections")
    public ApiResponse<List<Map<String, Object>>> mcpConnections() {
        return ApiResponse.ok(ecosystem.mcpConnections(user.tenantId()));
    }

    @PostMapping("/mcp/connections")
    public ApiResponse<Map<String, Object>> saveMcp(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = ecosystem.saveMcpConnection(user.tenantId(), body);
        record("ecosystem_mcp", "Save MCP connection", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/mcp/connections/{id}/probe")
    public ApiResponse<Map<String, Object>> probeMcp(@PathVariable long id) {
        Map<String, Object> result = ecosystem.probeMcp(user.tenantId(), id);
        record("ecosystem_mcp", "Probe MCP connection", String.valueOf(id));
        return ApiResponse.ok(result);
    }

    @GetMapping("/developer-apps")
    public ApiResponse<List<Map<String, Object>>> developerApps() {
        return ApiResponse.ok(ecosystem.developerApps(user.tenantId()));
    }

    @PostMapping("/developer-apps")
    public ApiResponse<Map<String, Object>> createDeveloperApp(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = ecosystem.createDeveloperApp(user.tenantId(), user.userId(), body);
        record("ecosystem_gateway", "Create developer application", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/developer-portal")
    public ApiResponse<Map<String, Object>> developerPortal() {
        return ApiResponse.ok(ecosystem.developerPortal(user.tenantId()));
    }

    @GetMapping("/multimodal/jobs")
    public ApiResponse<List<Map<String, Object>>> multimodalJobs() {
        return ApiResponse.ok(ecosystem.multimodalJobs(user.tenantId()));
    }

    @PostMapping("/multimodal/extract")
    public ApiResponse<Map<String, Object>> extractMedia(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = ecosystem.extractMedia(user.tenantId(), user.userId(), body);
        record("ecosystem_multimodal", "Extract multimodal asset", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/deployment/plan")
    public ApiResponse<Map<String, Object>> deploymentPlan() {
        return ApiResponse.ok(ecosystem.deploymentPlan());
    }

    @GetMapping("/worker-pools")
    public ApiResponse<List<Map<String, Object>>> workerPools() {
        return ApiResponse.ok(ecosystem.workerPools(user.tenantId()));
    }

    @PostMapping("/worker-pools/scale-plan")
    public ApiResponse<Map<String, Object>> scalePlan(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = ecosystem.scalePlan(user.tenantId(), body);
        record("ecosystem_worker", "Calculate worker scaling plan", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/resilience/drills")
    public ApiResponse<List<Map<String, Object>>> drills() {
        return ApiResponse.ok(ecosystem.drills(user.tenantId()));
    }

    @PostMapping("/resilience/drills")
    public ApiResponse<Map<String, Object>> resilienceDrill(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = ecosystem.resilienceDrill(user.tenantId(), user.userId(), body);
        record("ecosystem_resilience", "Run resilience dry run", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/health-report")
    public ApiResponse<Map<String, Object>> healthReport() {
        Map<String, Object> result = ecosystem.healthReport(user.tenantId());
        record("ecosystem_devkit", "Generate redacted health report", "tenant:" + user.tenantId());
        return ApiResponse.ok(result);
    }

    private void record(String type, String action, String detail) {
        audit.record(type, user.userId(), user.require().username(), action, detail, "success", user.tenantId());
    }
}
