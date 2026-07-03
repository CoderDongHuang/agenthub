package com.agenthub.user.controller;

import com.agenthub.common.response.ApiResponse;
import com.agenthub.user.dto.CreateUserRequest;
import com.agenthub.user.entity.Role;
import com.agenthub.user.entity.User;
import com.agenthub.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateUserRequest request) {
        User user = userService.createUser(request);
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("displayName", user.getDisplayName());
        return ApiResponse.ok(data);
    }

    @GetMapping
    public ApiResponse<Page<User>> list(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<User> users = userService.listUsers(pageable);
        return ApiResponse.ok(users);
    }

    @GetMapping("/{id}")
    public ApiResponse<User> get(@PathVariable Long id) {
        return ApiResponse.ok(userService.getUser(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<User> update(@PathVariable Long id, @RequestBody CreateUserRequest request) {
        return ApiResponse.ok(userService.updateUser(id, request));
    }

    @PutMapping("/{id}/disable")
    public ApiResponse<String> disable(@PathVariable Long id) {
        userService.disableUser(id);
        return ApiResponse.ok("用户已禁用");
    }

    @PutMapping("/{id}/enable")
    public ApiResponse<String> enable(@PathVariable Long id) {
        userService.enableUser(id);
        return ApiResponse.ok("用户已启用");
    }

    @GetMapping("/roles")
    public ApiResponse<List<Role>> roles() {
        return ApiResponse.ok(userService.getAllRoles());
    }
}
