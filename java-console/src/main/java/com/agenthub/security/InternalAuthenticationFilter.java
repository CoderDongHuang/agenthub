package com.agenthub.security;

import com.agenthub.common.config.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;

@Component
public class InternalAuthenticationFilter extends OncePerRequestFilter {
    private static final Set<String> EXACT_INTERNAL_PATHS = Set.of(
            "/api/approvals/create",
            "/api/tools/register",
            "/api/knowledge/docs/chunks"
    );

    private final byte[] expectedToken;

    public InternalAuthenticationFilter(@Value("${agenthub.internal-token}") String internalToken) {
        if (internalToken == null || internalToken.isBlank() || internalToken.length() < 32) {
            throw new IllegalStateException("AGENTHUB_INTERNAL_TOKEN must contain at least 32 characters");
        }
        this.expectedToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean mandatoryInternal = path.startsWith("/api/internal/")
                || EXACT_INTERNAL_PATHS.contains(path)
                || path.matches("/api/knowledge/docs/\\d+/chunks");
        boolean sharedInternalRead = request.getHeader("X-Internal-Token") != null
                && ((path.matches("/api/knowledge/docs/\\d+") && "GET".equals(request.getMethod()))
                || (path.matches("/api/approvals/\\d+") && "GET".equals(request.getMethod())));
        return !mandatoryInternal && !sharedInternalRead;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader("X-Internal-Token");
        if (provided == null || !MessageDigest.isEqual(expectedToken, provided.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid internal service credentials");
            return;
        }

        Long tenantId;
        try {
            tenantId = Long.valueOf(request.getHeader("X-Tenant-Id"));
            if (tenantId < 0) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "A valid X-Tenant-Id header is required");
            return;
        }

        AuthenticatedUser principal = new AuthenticatedUser(0L, tenantId, "python-runtime");
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        TenantContext.set(tenantId);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
