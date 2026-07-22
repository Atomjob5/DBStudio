package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** 验证请求线程结束后不会把 MDC 标识泄漏到线程池的下一次任务。 */
class LoggingContextTest {
    @Test
    void contextIsRestoredAfterClose() {
        MDC.clear();
        MDC.put("requestId", "outer");
        try (LoggingContext ignored = LoggingContext.open("inner", "workspace", "client", "editor", "execution")) {
            assertEquals("inner", MDC.get("requestId"));
            assertEquals("workspace", MDC.get("workspaceId"));
            assertEquals("execution", MDC.get("executionId"));
        }
        assertEquals("outer", MDC.get("requestId"));
        assertNull(MDC.get("workspaceId"));
        MDC.clear();
    }
}
