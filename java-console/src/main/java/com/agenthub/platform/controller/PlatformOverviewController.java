package com.agenthub.platform.controller;

import com.agenthub.common.response.ApiResponse;
import com.agenthub.platform.service.PlatformOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/platform")
public class PlatformOverviewController {

    private final PlatformOverviewService overviewService;

    public PlatformOverviewController(PlatformOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.ok(overviewService.getOverview());
    }
}
