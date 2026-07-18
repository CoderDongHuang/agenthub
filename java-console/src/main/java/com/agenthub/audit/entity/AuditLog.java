package com.agenthub.audit.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 0L;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 50)
    private String username;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "agent_name", length = 100)
    private String agentName;

    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(nullable = false, length = 200)
    private String action;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(length = 20)
    private String result;

    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
