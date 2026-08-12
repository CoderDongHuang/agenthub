package com.agenthub.platform.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
public record WorkspaceResourceRequest(@NotBlank @Size(max=150) String name,
 @Size(max=2000) String description, String status, Map<String,Object> config) {}
