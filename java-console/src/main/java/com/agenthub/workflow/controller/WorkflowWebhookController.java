package com.agenthub.workflow.controller;

import com.agenthub.common.response.ApiResponse;
import com.agenthub.workflow.service.WorkflowAutomationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/hooks/workflows")
public class WorkflowWebhookController {

    private final WorkflowAutomationService workflows;

    public WorkflowWebhookController(WorkflowAutomationService workflows) {
        this.workflows = workflows;
    }

    @PostMapping("/{triggerKey}")
    public ApiResponse<Map<String, Object>> trigger(@PathVariable String triggerKey,
                                                    @RequestHeader("X-Workflow-Secret") String secret,
                                                    @RequestBody(required = false) Map<String, Object> payload) {
        return ApiResponse.ok(workflows.fireWebhook(triggerKey, secret, payload == null ? Map.of() : payload));
    }
}
