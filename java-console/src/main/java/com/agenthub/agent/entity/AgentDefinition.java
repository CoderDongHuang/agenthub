package com.agenthub.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "agent_definition")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    @Builder.Default
    private Long tenantId = 0L;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "system_prompt", nullable = false, columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String model = "gpt-4o";

    @Column(precision = 3, scale = 2)
    @Builder.Default
    private BigDecimal temperature = BigDecimal.valueOf(0.7);

    @Column(name = "max_tokens")
    @Builder.Default
    private Integer maxTokens = 4096;

    @Column(length = 20)
    @Builder.Default
    private String status = "draft";

    @Column(length = 50)
    private String icon;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
