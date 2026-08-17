package com.agenthub.governance.controller;

import com.agenthub.audit.service.AuditService;
import com.agenthub.common.response.ApiResponse;
import com.agenthub.governance.service.EnterpriseGovernanceService;
import com.agenthub.governance.service.GovernanceGuardrailService;
import com.agenthub.governance.service.GovernancePolicyEvaluator;
import com.agenthub.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/governance")
public class EnterpriseGovernanceController {
    private final EnterpriseGovernanceService governance;
    private final GovernanceGuardrailService guardrails;
    private final CurrentUser user;
    private final AuditService audit;

    public EnterpriseGovernanceController(EnterpriseGovernanceService governance,
                                          GovernanceGuardrailService guardrails,
                                          CurrentUser user, AuditService audit) {
        this.governance = governance;
        this.guardrails = guardrails;
        this.user = user;
        this.audit = audit;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(governance.overview(user.tenantId()));
    }

    @GetMapping("/identity/providers")
    public ApiResponse<List<Map<String, Object>>> identityProviders() {
        return ApiResponse.ok(governance.identityProviders(user.tenantId()));
    }

    @PostMapping("/identity/providers")
    public ApiResponse<Map<String, Object>> saveIdentityProvider(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = governance.saveIdentityProvider(user.tenantId(), body);
        record("governance_identity", "Validate identity provider", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/scim/tokens")
    public ApiResponse<List<Map<String, Object>>> scimTokens() {
        return ApiResponse.ok(governance.scimTokens(user.tenantId()));
    }

    @PostMapping("/scim/tokens")
    public ApiResponse<Map<String, Object>> issueScimToken(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = governance.issueScimToken(user.tenantId(), user.userId(), body);
        record("governance_identity", "Issue SCIM token", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @DeleteMapping("/scim/tokens/{id}")
    public ApiResponse<String> revokeScimToken(@PathVariable long id) {
        governance.revokeScimToken(user.tenantId(), id);
        record("governance_identity", "Revoke SCIM token", String.valueOf(id));
        return ApiResponse.ok("SCIM token revoked");
    }

    @PostMapping("/scim/v2/Users")
    public ApiResponse<Map<String, Object>> syncScimUser(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = governance.syncScimUser(user.tenantId(), body);
        record("governance_identity", "SCIM user sync", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/scim/v2/Groups")
    public ApiResponse<Map<String, Object>> syncScimGroup(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = governance.syncScimGroup(user.tenantId(), body);
        record("governance_identity", "SCIM group sync", String.valueOf(result.get("id")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/access-policies")
    public ApiResponse<List<Map<String, Object>>> accessPolicies() {
        return ApiResponse.ok(governance.accessPolicies(user.tenantId()));
    }

    @PostMapping("/access-policies")
    public ApiResponse<Map<String, Object>> saveAccessPolicy(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.saveAccessPolicy(user.tenantId(), user.userId(), body));
    }

    @PostMapping("/access-policies/evaluate")
    public ApiResponse<GovernancePolicyEvaluator.AccessDecision> evaluateAccess(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.evaluateAccess(user.tenantId(), body));
    }

    @GetMapping("/secrets")
    public ApiResponse<List<Map<String, Object>>> secrets() {
        return ApiResponse.ok(governance.secrets(user.tenantId()));
    }

    @PostMapping("/secrets")
    public ApiResponse<Map<String, Object>> storeSecret(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = governance.storeSecret(user.tenantId(), user.userId(), body);
        record("governance_kms", "Store encrypted secret", String.valueOf(result.get("secretKey")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/secrets/{id}/reveal")
    public ApiResponse<Map<String, Object>> revealSecret(@PathVariable long id) {
        Map<String, Object> result = governance.revealSecret(user.tenantId(), id);
        record("governance_kms", "Reveal encrypted secret", String.valueOf(result.get("secretKey")));
        return ApiResponse.ok(result);
    }

    @PostMapping("/secrets/rotate-key")
    public ApiResponse<Map<String, Object>> rotateTenantKey() {
        Map<String, Object> result = governance.rotateTenantKey(user.tenantId());
        record("governance_kms", "Rotate tenant key", String.valueOf(result.get("activeVersion")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/retention-policies")
    public ApiResponse<List<Map<String, Object>>> retentionPolicies() {
        return ApiResponse.ok(governance.retentionPolicies(user.tenantId()));
    }

    @PostMapping("/retention-policies")
    public ApiResponse<Map<String, Object>> saveRetentionPolicy(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.saveRetentionPolicy(user.tenantId(), body));
    }

    @PostMapping("/retention-policies/{id}/run")
    public ApiResponse<Map<String, Object>> runRetention(@PathVariable long id,
                                                         @RequestParam(defaultValue = "false") boolean execute) {
        Map<String, Object> result = governance.runRetention(user.tenantId(), id, execute);
        record("governance_compliance", execute ? "Execute retention" : "Preview retention", String.valueOf(id));
        return ApiResponse.ok(result);
    }

    @GetMapping("/compliance-report")
    public ApiResponse<Map<String, Object>> complianceReport() {
        return ApiResponse.ok(governance.complianceReport(user.tenantId()));
    }

    @PostMapping("/guardrails/scan")
    public ApiResponse<Map<String, Object>> scan(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = guardrails.scan(body);
        if (!Boolean.TRUE.equals(result.get("allowed"))) record("governance_guardrail", "Block unsafe input", String.valueOf(result.get("findings")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/approval-policies")
    public ApiResponse<List<Map<String, Object>>> approvalPolicies() {
        return ApiResponse.ok(governance.approvalPolicies(user.tenantId()));
    }

    @PostMapping("/approval-policies")
    public ApiResponse<Map<String, Object>> saveApprovalPolicy(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.saveApprovalPolicy(user.tenantId(), body));
    }

    @PostMapping("/approval-policies/evaluate")
    public ApiResponse<GovernancePolicyEvaluator.ApprovalDecision> evaluateApproval(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.evaluateApproval(user.tenantId(), body));
    }

    @GetMapping("/on-call")
    public ApiResponse<List<Map<String, Object>>> onCallSchedules() {
        return ApiResponse.ok(governance.onCallSchedules(user.tenantId()));
    }

    @PostMapping("/on-call")
    public ApiResponse<Map<String, Object>> saveOnCall(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.saveOnCall(user.tenantId(), body));
    }

    @PostMapping("/approval-sla/sweep")
    public ApiResponse<Map<String, Object>> sweepApprovalSla() {
        Map<String, Object> result = governance.sweepApprovalSla(user.tenantId());
        record("governance_approval", "Sweep approval SLA", String.valueOf(result.get("escalated")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/jobs")
    public ApiResponse<List<Map<String, Object>>> jobs() {
        return ApiResponse.ok(governance.jobs(user.tenantId()));
    }

    @PostMapping("/exports")
    public ApiResponse<Map<String, Object>> createExport() {
        return ApiResponse.ok(governance.createExport(user.tenantId(), user.userId()));
    }

    @PostMapping("/backups")
    public ApiResponse<Map<String, Object>> createBackup() {
        return ApiResponse.ok(governance.createBackup(user.tenantId(), user.userId()));
    }

    @PostMapping("/jobs/{id}/verify")
    public ApiResponse<Map<String, Object>> verifyJob(@PathVariable UUID id) {
        return ApiResponse.ok(governance.verifyJob(user.tenantId(), id));
    }

    @PostMapping("/backups/{id}/restore-drill")
    public ApiResponse<Map<String, Object>> restoreDrill(@PathVariable UUID id) {
        return ApiResponse.ok(governance.restoreDrill(user.tenantId(), user.userId(), id));
    }

    @PostMapping("/migrations")
    public ApiResponse<Map<String, Object>> migrationPlan(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(governance.migrationPlan(user.tenantId(), user.userId(), body));
    }

    @PostMapping("/deletions")
    public ApiResponse<Map<String, Object>> deleteAndCertify(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = governance.deleteAndCertify(user.tenantId(), user.userId(), body);
        record("governance_deletion", "Delete and certify", String.valueOf(result.get("certificateId")));
        return ApiResponse.ok(result);
    }

    @GetMapping("/deletions/{id}/verify")
    public ApiResponse<Map<String, Object>> verifyDeletion(@PathVariable UUID id) {
        return ApiResponse.ok(governance.verifyDeletion(user.tenantId(), id));
    }

    private void record(String type, String action, String detail) {
        audit.record(type, user.userId(), user.require().username(), action, detail, "success", user.tenantId());
    }
}
