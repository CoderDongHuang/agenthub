package com.agenthub.common.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 租户过滤器 — 从请求头 X-Tenant-Id 提取租户信息，存入 TenantContext
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TenantFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            HttpServletRequest httpReq = (HttpServletRequest) request;
            String tenantHeader = httpReq.getHeader("X-Tenant-Id");
            Long tenantId = 0L;
            if (tenantHeader != null && !tenantHeader.isBlank()) {
                try {
                    tenantId = Long.valueOf(tenantHeader);
                } catch (NumberFormatException ignored) {}
            }
            TenantContext.set(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
