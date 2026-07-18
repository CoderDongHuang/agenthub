package com.agenthub.audit.controller;

import com.agenthub.audit.entity.AuditLog;
import com.agenthub.audit.service.AuditService;
import com.agenthub.common.response.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse<Page<AuditLog>> list(
            @PageableDefault(size = 30, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        if (eventType != null || userId != null || startTime != null || endTime != null) {
            return ApiResponse.ok(auditService.search(eventType, userId, startTime, endTime, pageable));
        }
        return ApiResponse.ok(auditService.list(pageable));
    }

    @GetMapping("/recent")
    public ApiResponse<Map<String, Object>> recent(@RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(auditService.recentEvents(limit));
    }
}
