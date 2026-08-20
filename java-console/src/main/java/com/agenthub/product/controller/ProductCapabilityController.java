package com.agenthub.product.controller;

import com.agenthub.common.response.ApiResponse;
import com.agenthub.diagnostics.service.ConfigurationDiagnosticsService;
import com.agenthub.knowledge.service.KnowledgeEvolutionService;
import com.agenthub.observability.service.TraceService;
import com.agenthub.release.service.AgentVersionService;
import com.agenthub.release.service.EvaluationService;
import com.agenthub.routing.service.ModelRoutingService;
import com.agenthub.security.CurrentUser;
import com.agenthub.workflow.service.WorkflowAutomationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/product")
public class ProductCapabilityController {

    private final CurrentUser user;
    private final AgentVersionService versions;
    private final EvaluationService evaluations;
    private final TraceService traces;
    private final ModelRoutingService routing;
    private final ConfigurationDiagnosticsService diagnostics;
    private final KnowledgeEvolutionService knowledge;
    private final WorkflowAutomationService workflows;

    public ProductCapabilityController(CurrentUser user, AgentVersionService versions,
                                       EvaluationService evaluations, TraceService traces,
                                       ModelRoutingService routing, ConfigurationDiagnosticsService diagnostics,
                                       KnowledgeEvolutionService knowledge, WorkflowAutomationService workflows) {
        this.user = user;
        this.versions = versions;
        this.evaluations = evaluations;
        this.traces = traces;
        this.routing = routing;
        this.diagnostics = diagnostics;
        this.knowledge = knowledge;
        this.workflows = workflows;
    }

    @GetMapping("/agents/{agentId}/versions")
    public ApiResponse<List<Map<String, Object>>> versions(@PathVariable Long agentId) {
        return ApiResponse.ok(versions.list(user.tenantId(), agentId));
    }

    @PostMapping("/agents/{agentId}/versions")
    public ApiResponse<Map<String, Object>> snapshot(@PathVariable Long agentId,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(versions.snapshot(user.tenantId(), user.userId(), agentId,
                body == null ? null : String.valueOf(body.getOrDefault("note", ""))));
    }

    @GetMapping("/agents/{agentId}/versions/diff")
    public ApiResponse<Map<String, Object>> diff(@PathVariable Long agentId,
                                                 @RequestParam Long left, @RequestParam Long right) {
        return ApiResponse.ok(versions.diff(user.tenantId(), agentId, left, right));
    }

    @PostMapping("/agents/{agentId}/versions/{versionId}/release")
    public ApiResponse<Map<String, Object>> release(@PathVariable Long agentId, @PathVariable Long versionId,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        int rollout = body == null ? 100 : Integer.parseInt(String.valueOf(body.getOrDefault("rolloutPercent", 100)));
        return ApiResponse.ok(versions.release(user.tenantId(), agentId, versionId, rollout));
    }

    @PostMapping("/agents/{agentId}/versions/{versionId}/rollback")
    public ApiResponse<Map<String, Object>> rollback(@PathVariable Long agentId, @PathVariable Long versionId) {
        return ApiResponse.ok(versions.rollback(user.tenantId(), agentId, versionId));
    }

    @GetMapping("/evaluations/datasets")
    public ApiResponse<List<Map<String, Object>>> datasets() {
        return ApiResponse.ok(evaluations.listDatasets(user.tenantId()));
    }

    @PostMapping("/evaluations/datasets")
    public ApiResponse<Map<String, Object>> createDataset(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(evaluations.createDataset(user.tenantId(), user.userId(), body));
    }

    @GetMapping("/evaluations/datasets/{datasetId}")
    public ApiResponse<Map<String, Object>> dataset(@PathVariable Long datasetId) {
        return ApiResponse.ok(evaluations.getDataset(user.tenantId(), datasetId));
    }

    @PostMapping("/evaluations/datasets/{datasetId}/cases")
    public ApiResponse<Map<String, Object>> addCase(@PathVariable Long datasetId,
                                                    @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(evaluations.addCase(user.tenantId(), datasetId, body));
    }

    @PostMapping("/evaluations/datasets/{datasetId}/run")
    public ApiResponse<Map<String, Object>> runEvaluation(@PathVariable Long datasetId,
                                                          @RequestParam(required = false) Long agentId,
                                                          @RequestParam(required = false) Long versionId,
                                                          @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(evaluations.run(user.tenantId(), datasetId, agentId, versionId, body));
    }

    @GetMapping("/evaluations/runs")
    public ApiResponse<List<Map<String, Object>>> evaluationRuns(@RequestParam(defaultValue = "30") int limit) {
        return ApiResponse.ok(evaluations.listRuns(user.tenantId(), limit));
    }

    @GetMapping("/traces")
    public ApiResponse<List<Map<String, Object>>> traceList(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(traces.list(user.tenantId(), limit));
    }

    @GetMapping("/traces/summary")
    public ApiResponse<Map<String, Object>> traceSummary() {
        return ApiResponse.ok(traces.summary(user.tenantId()));
    }

    @GetMapping("/traces/{traceId}")
    public ApiResponse<Map<String, Object>> trace(@PathVariable UUID traceId) {
        return ApiResponse.ok(traces.get(user.tenantId(), traceId));
    }

    @GetMapping("/observability/overview")
    public ApiResponse<Map<String, Object>> observability(@RequestParam(defaultValue = "24") int hours) {
        return ApiResponse.ok(traces.observability(user.tenantId(), hours));
    }

    @PostMapping("/observability/traces/{traceId}/replay")
    public ApiResponse<Map<String, Object>> replay(@PathVariable UUID traceId) {
        return ApiResponse.ok(traces.replay(user.tenantId(), traceId));
    }

    @PostMapping("/observability/traces/{traceId}/feedback")
    public ApiResponse<Map<String, Object>> feedback(@PathVariable UUID traceId,
                                                     @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(traces.addFeedback(user.tenantId(), user.userId(), traceId,
                Integer.parseInt(String.valueOf(body.getOrDefault("rating", 0))),
                String.valueOf(body.getOrDefault("comment", ""))));
    }

    @GetMapping("/observability/traces/{traceId}/feedback")
    public ApiResponse<List<Map<String, Object>>> feedback(@PathVariable UUID traceId) {
        return ApiResponse.ok(traces.feedback(user.tenantId(), traceId));
    }

    @PostMapping("/observability/evaluations/compare")
    public ApiResponse<Map<String, Object>> compareEvaluations(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(evaluations.compareVersions(user.tenantId(),
                Long.parseLong(String.valueOf(body.get("datasetId"))),
                Long.parseLong(String.valueOf(body.get("baselineVersionId"))),
                Long.parseLong(String.valueOf(body.get("candidateVersionId")))));
    }

    @PostMapping("/observability/agents/{agentId}/versions/{candidateVersionId}/canary-guard")
    public ApiResponse<Map<String, Object>> canaryGuard(@PathVariable Long agentId,
                                                        @PathVariable Long candidateVersionId,
                                                        @RequestBody Map<String, Object> body) {
        BigDecimal minimumDelta = new BigDecimal(String.valueOf(body.getOrDefault("minimumScoreDelta", 0)));
        return ApiResponse.ok(versions.guardCanary(user.tenantId(), agentId, candidateVersionId,
                Long.parseLong(String.valueOf(body.get("baselineVersionId"))), minimumDelta));
    }

    @GetMapping("/routing/endpoints")
    public ApiResponse<List<Map<String, Object>>> endpoints() {
        return ApiResponse.ok(routing.list(user.tenantId()));
    }

    @PostMapping("/routing/endpoints")
    public ApiResponse<Map<String, Object>> upsertEndpoint(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(routing.upsert(user.tenantId(), body));
    }

    @PostMapping("/routing/decide")
    public ApiResponse<ModelRoutingService.RouteDecision> route(@RequestBody Map<String, Object> body) {
        String model = String.valueOf(body.getOrDefault("preferredModel", "deepseek-v4-flash"));
        @SuppressWarnings("unchecked")
        Map<String, Object> constraints = body.get("constraints") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return ApiResponse.ok(routing.route(user.tenantId(), model, constraints));
    }

    @PostMapping("/routing/endpoints/{endpointId}/health")
    public ApiResponse<Map<String, Object>> reportHealth(@PathVariable Long endpointId,
                                                         @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(routing.report(user.tenantId(), endpointId,
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("success", false))),
                Long.parseLong(String.valueOf(body.getOrDefault("latencyMs", 0))),
                String.valueOf(body.getOrDefault("error", ""))));
    }

    @PostMapping("/routing/endpoints/{endpointId}/probe")
    public ApiResponse<Map<String, Object>> probe(@PathVariable Long endpointId) {
        return ApiResponse.ok(routing.probe(user.tenantId(), endpointId));
    }

    @GetMapping("/diagnostics")
    public ApiResponse<Map<String, Object>> diagnostics() {
        return ApiResponse.ok(diagnostics.diagnose(user.tenantId()));
    }

    @GetMapping("/knowledge/{kbId}/sources")
    public ApiResponse<List<Map<String, Object>>> knowledgeSources(@PathVariable Long kbId) {
        return ApiResponse.ok(knowledge.listSources(user.tenantId(), kbId));
    }

    @PostMapping("/knowledge/{kbId}/sources")
    public ApiResponse<Map<String, Object>> createKnowledgeSource(@PathVariable Long kbId,
                                                                  @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(knowledge.createSource(user.tenantId(), kbId, body));
    }

    @PostMapping("/knowledge/sources/{sourceId}/sync")
    public ApiResponse<Map<String, Object>> syncKnowledge(@PathVariable Long sourceId,
                                                          @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(knowledge.sync(user.tenantId(), sourceId, body));
    }

    @PostMapping("/knowledge/{kbId}/evaluate")
    public ApiResponse<Map<String, Object>> evaluateKnowledge(@PathVariable Long kbId,
                                                              @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(knowledge.evaluate(user.tenantId(), kbId, body));
    }

    @GetMapping("/knowledge/{kbId}/evaluation-runs")
    public ApiResponse<List<Map<String, Object>>> knowledgeRuns(@PathVariable Long kbId) {
        return ApiResponse.ok(knowledge.evaluationRuns(user.tenantId(), kbId));
    }

    @GetMapping("/knowledge/{kbId}/operations")
    public ApiResponse<Map<String, Object>> knowledgeOperations(@PathVariable Long kbId) {
        return ApiResponse.ok(knowledge.overview(user.tenantId(), kbId));
    }

    @GetMapping("/knowledge/{kbId}/index-versions")
    public ApiResponse<List<Map<String, Object>>> knowledgeIndexVersions(@PathVariable Long kbId) {
        return ApiResponse.ok(knowledge.indexVersions(user.tenantId(), kbId));
    }

    @PostMapping("/knowledge/{kbId}/rebuild")
    public ApiResponse<Map<String, Object>> rebuildKnowledgeIndex(@PathVariable Long kbId) {
        return ApiResponse.ok(knowledge.rebuild(user.tenantId(), kbId));
    }

    @GetMapping("/workflows/templates")
    public ApiResponse<List<Map<String, Object>>> workflowTemplates() {
        return ApiResponse.ok(workflows.templates(user.tenantId()));
    }

    @PostMapping("/workflows/templates")
    public ApiResponse<Map<String, Object>> createWorkflowTemplate(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workflows.createTemplate(user.tenantId(), user.userId(), body));
    }

    @PostMapping("/workflows/templates/{templateId}/instantiate")
    public ApiResponse<Map<String, Object>> instantiateWorkflow(@PathVariable Long templateId,
                                                                @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(workflows.instantiate(user.tenantId(), user.userId(), templateId,
                body == null ? Map.of() : body));
    }

    @PostMapping("/workflows/{workflowId}/subflows/{templateId}")
    public ApiResponse<Map<String, Object>> addSubflow(@PathVariable Long workflowId, @PathVariable Long templateId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(workflows.attachSubflow(user.tenantId(), workflowId, templateId,
                body == null ? Map.of() : body));
    }

    @GetMapping("/workflows/triggers")
    public ApiResponse<List<Map<String, Object>>> workflowTriggers() {
        return ApiResponse.ok(workflows.triggers(user.tenantId()));
    }

    @PostMapping("/workflows/triggers")
    public ApiResponse<Map<String, Object>> createWorkflowTrigger(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(workflows.createTrigger(user.tenantId(), user.userId(), body));
    }
}
