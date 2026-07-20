package com.agenthub.common.config;

/**
 * 租户上下文 — ThreadLocal 持有当前请求的 tenant_id
 */
public class TenantContext {
    private static final ThreadLocal<Long> CURRENT_TENANT = new ThreadLocal<>();

    public static void set(Long tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    public static Long get() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
