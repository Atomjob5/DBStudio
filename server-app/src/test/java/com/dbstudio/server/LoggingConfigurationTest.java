package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 验证 Logback 能携带请求诊断字段，便于按 requestId 或 Workspace 还原链路。 */
class LoggingConfigurationTest {
    @Test
    void logEventContainsMdcCorrelationFields() {
        Logger logger = (Logger) LoggerFactory.getLogger("com.dbstudio.logging-test");
        ListAppender<ILoggingEvent> appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (LoggingContext ignored = LoggingContext.open("request-1", "workspace-1", "client-1",
                "editor-1", "execution-1")) {
            logger.info("测试日志");
        } finally {
            logger.detachAppender(appender);
        }
        ILoggingEvent event = appender.list.get(0);
        assertEquals("request-1", event.getMDCPropertyMap().get("requestId"));
        assertEquals("workspace-1", event.getMDCPropertyMap().get("workspaceId"));
        assertEquals("client-1", event.getMDCPropertyMap().get("clientId"));
        assertEquals("execution-1", event.getMDCPropertyMap().get("executionId"));
    }
}
