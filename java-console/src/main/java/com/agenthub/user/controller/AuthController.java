package com.agenthub.user.controller;

import com.agenthub.common.response.ApiResponse;
import com.agenthub.user.dto.CreateUserRequest;
import com.agenthub.user.dto.LoginRequest;
import com.agenthub.user.dto.LoginResponse;
import com.agenthub.user.entity.User;
import com.agenthub.user.service.AuthService;
import com.agenthub.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse result = authService.login(request);
        return ApiResponse.ok(result);
    }

    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.createUser(request);
        return ApiResponse.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName()
        ));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication authentication) {
        User user = userService.getUserByUsername(authentication.getName());
        List<String> roles = user.getRoles().stream()
                .map(role -> role.getRoleCode())
                .toList();
        return ApiResponse.ok(Map.of(
                "userId", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName(),
                "email", user.getEmail() == null ? "" : user.getEmail(),
                "roles", roles
        ));
    }
}
