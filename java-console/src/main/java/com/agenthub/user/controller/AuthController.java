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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;
    private final boolean secureCookie;

    public AuthController(AuthService authService, UserService userService,
                          @Value("${agenthub.auth.secure-cookie:false}") boolean secureCookie) {
        this.authService = authService;
        this.userService = userService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        LoginResponse result = authService.login(request);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(result.getToken(), 3600).toString());
        result.setToken(null);
        return ApiResponse.ok(result);
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.ok(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", 0).toString());
        return ApiResponse.ok("Logged out");
    }

    private ResponseCookie sessionCookie(String value, long maxAge) {
        return ResponseCookie.from("AGENTHUB_SESSION", value)
                .httpOnly(true).secure(secureCookie).sameSite("Strict")
                .path("/").maxAge(maxAge).build();
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
