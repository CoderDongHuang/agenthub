package com.agenthub.audit.service;

import com.agenthub.audit.entity.AuditLog;
import com.agenthub.audit.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public AuditLog record(AuditLog log) {
        return auditLogRepository.save(log);
    }

    public void record(String eventType, Long userId, String username,
                       String action, String detail, String result) {
        AuditLog log = AuditLog.builder()
                .eventType(eventType)
                .userId(userId)
                .username(username)
                .action(action)
                .detail(detail)
                .result(result)
                .tenantId(0L)
                .build();
        auditLogRepository.save(log);
    }

    public Page<AuditLog> list(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    public Page<AuditLog> search(String eventType, Long userId,
                                  LocalDateTime startTime, LocalDateTime endTime,
                                  Pageable pageable) {
        return auditLogRepository.search(eventType, userId, startTime, endTime, pageable);
    }

    public Map<String, Object> recentEvents(int limit) {
        Page<AuditLog> page = auditLogRepository.findAllByOrderByCreatedAtDesc(
                Pageable.ofSize(limit)
        );
        return Map.of(
                "total", page.getTotalElements(),
                "items", page.getContent()
        );
    }
}
