package com.agenthub.user.service;

import com.agenthub.user.dto.CreateUserRequest;
import com.agenthub.user.entity.Role;
import com.agenthub.user.entity.User;
import com.agenthub.user.repository.RoleRepository;
import com.agenthub.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.agenthub.common.config.TenantContext;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User createUser(CreateUserRequest request) {
        Long tenantId = currentTenant();
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("用户名已存在: " + request.getUsername());
        }

        Set<Role> roles = new HashSet<>();
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            roles = new HashSet<>(roleRepository.findByIdInAndTenantId(request.getRoleIds(), tenantId));
        }
        // 默认给 agent_user 角色
        if (roles.isEmpty()) {
            roleRepository.findByRoleCodeAndTenantId("agent_user", tenantId).ifPresent(roles::add);
        }

        User user = User.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName())
                .email(request.getEmail())
                .phone(request.getPhone())
                .departmentId(request.getDepartmentId())
                .tenantId(tenantId)
                .status("active")
                .roles(roles)
                .build();

        return userRepository.save(user);
    }

    public Page<User> listUsers(Pageable pageable) {
        return userRepository.findByTenantId(currentTenant(), pageable);
    }

    public User getUser(Long id) {
        return userRepository.findByIdAndTenantId(id, currentTenant())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + id));
    }

    public User getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + username));
    }

    public User updateUser(Long id, CreateUserRequest request) {
        User user = getUser(id);

        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName());
        }
        if (request.getEmail() != null) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }
        if (request.getRoleIds() != null && !request.getRoleIds().isEmpty()) {
            Set<Role> roles = new HashSet<>(
                    roleRepository.findByIdInAndTenantId(request.getRoleIds(), currentTenant()));
            user.setRoles(roles);
        }

        return userRepository.save(user);
    }

    public void disableUser(Long id) {
        User user = getUser(id);
        user.setStatus("disabled");
        userRepository.save(user);
    }

    public void enableUser(Long id) {
        User user = getUser(id);
        user.setStatus("active");
        userRepository.save(user);
    }

    public List<Role> getAllRoles() {
        return roleRepository.findByTenantId(currentTenant());
    }

    private Long currentTenant() {
        return TenantContext.get() != null ? TenantContext.get() : 0L;
    }
}
