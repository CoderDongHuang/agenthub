package com.agenthub.audit.aspect;

import com.agenthub.audit.annotation.Auditable;
import com.agenthub.audit.entity.AuditLog;
import com.agenthub.audit.repository.AuditLogRepository;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 审计切面 — 拦截 @Auditable 方法，自动记录执行结果
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private final AuditLogRepository auditLogRepository;

    public AuditAspect(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint jp, Auditable auditable) throws Throwable {
        String eventType = auditable.eventType();
        String action = auditable.action().isEmpty() ? jp.getSignature().getName() : auditable.action();
        String detail = auditable.detail().isEmpty()
                ? Arrays.toString(jp.getArgs())
                : auditable.detail();

        String result = "success";
        long start = System.currentTimeMillis();
        try {
            Object ret = jp.proceed();
            log.debug("Audit [{}] {}: {} ({}ms)", eventType, action, detail, System.currentTimeMillis() - start);
            return ret;
        } catch (Throwable t) {
            result = "failed: " + t.getMessage();
            throw t;
        } finally {
            try {
                auditLogRepository.save(AuditLog.builder()
                        .eventType(eventType)
                        .action(action)
                        .detail(detail)
                        .result(result)
                        .username("system")
                        .tenantId(0L)
                        .build());
            } catch (Exception e) {
                log.warn("Failed to write audit log: {}", e.getMessage());
            }
        }
    }
}
