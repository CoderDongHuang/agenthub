package com.agenthub.audit.annotation;

import java.lang.annotation.*;

/**
 * 审计注解 — 标记在方法上，执行后自动记录审计日志
 *
 * 用法:
 * @Auditable(eventType = "agent_create", action = "创建Agent")
 * public AgentDefinition createAgent(...) { ... }
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Auditable {
    String eventType() default "system_action";
    String action() default "";
    String detail() default "";
}
