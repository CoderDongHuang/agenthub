package com.agenthub.approval.controller;

import com.agenthub.approval.entity.ApprovalRequest;
import com.agenthub.approval.service.ApprovalService;
import com.agenthub.audit.service.AuditService;
import com.agenthub.common.response.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import com.agenthub.security.CurrentUser;

@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {

    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public ApprovalController(ApprovalService approvalService, AuditService auditService, CurrentUser currentUser) {
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        if (body.get("requesterId") == null) {
            return ApiResponse.error(400, "requesterId is required");
        }
        ApprovalRequest req = approvalService.createRequest(
                (String) body.getOrDefault("sessionId", ""),
                body.get("agentId") != null ? Long.valueOf(body.get("agentId").toString()) : null,
                null,
                (String) body.getOrDefault("toolName", ""),
                Long.valueOf(body.get("requesterId").toString()),
                (String) body.getOrDefault("reason", ""),
                (String) body.getOrDefault("context", "")
        );
        return ApiResponse.ok(Map.of("id", req.getId(), "status", req.getStatus()));
    }

    @GetMapping("/pending")
    public ApiResponse<Page<ApprovalRequest>> listPending(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(approvalService.listPending(pageable));
    }

    @GetMapping("/my")
    public ApiResponse<Page<ApprovalRequest>> myRequests(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok(approvalService.listByRequester(currentUser.userId(), pageable));
    }

    @GetMapping("/{id}")
    public ApiResponse<ApprovalRequest> get(@PathVariable Long id) {
        return ApiResponse.ok(approvalService.getRequest(id));
    }

    @PutMapping("/{id}/approve")
    public ApiResponse<ApprovalRequest> approve(@PathVariable Long id) {
        ApprovalRequest req = approvalService.approve(id, "Approved");
        auditService.record("approval_action", currentUser.userId(), currentUser.require().username(),
                "Approve #" + id, req.getReason(), "approved", currentUser.tenantId());
        return ApiResponse.ok(req);
    }

    @PutMapping("/{id}/reject")
    public ApiResponse<ApprovalRequest> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "Rejected");
        return ApiResponse.ok(approvalService.reject(id, reason));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(approvalService.stats());
    }
}
