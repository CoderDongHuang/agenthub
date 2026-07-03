package com.agenthub.user.controller;

import com.agenthub.common.response.ApiResponse;
import com.agenthub.user.dto.LoginRequest;
import com.agenthub.user.dto.LoginResponse;
import com.agenthub.user.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse result = authService.login(request);
        return ApiResponse.ok(result);
    }

    @GetMapping("/me")
    public ApiResponse<String> me() {
        return ApiResponse.ok("当前用户信息（Phase 1 实现）");
    }
}
