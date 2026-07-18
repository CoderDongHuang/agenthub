package com.agenthub.approval.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "approval_request")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 0L;

    @Column(name = "session_id", length = 64)
    private String sessionId;

    @Column(name = "agent_id")
    private Long agentId;

    @Column(name = "tool_id")
    private Long toolId;

    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Column(name = "requester_id")
    private Long requesterId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String context;

    @Column(length = 20)
    @Builder.Default
    private String status = "pending";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
