package com.agenthub.user.service;

import com.agenthub.security.JwtTokenProvider;
import com.agenthub.user.dto.LoginRequest;
import com.agenthub.user.dto.LoginResponse;
import com.agenthub.user.entity.User;
import com.agenthub.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));

        if (!"active".equals(user.getStatus())) {
            throw new IllegalArgumentException("账户已被禁用");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        Set<String> roles = user.getRoles().stream()
                .map(r -> r.getRoleCode())
                .collect(Collectors.toSet());

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), roles);

        return LoginResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .roles(roles.stream().toList())
                .build();
    }
}
