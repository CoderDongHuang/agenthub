package com.agenthub.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class AgentCreateRequest {
    @NotBlank(message = "Agent 名称不能为空")
    @Size(max = 100)
    private String name;

    private String description;

    @NotBlank(message = "系统提示词不能为空")
    private String systemPrompt;

    private String model = "gpt-4o";

    private BigDecimal temperature = BigDecimal.valueOf(0.7);

    private Integer maxTokens = 4096;

    private String icon;
}
