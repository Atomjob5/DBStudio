package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

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

    @Test
    void sqlLoggersAreRoutedToSixNonAdditiveRollingFiles() throws Exception {
        String configuration = new String(Files.readAllBytes(Paths.get(
                "src/main/resources/logback-spring.xml")), StandardCharsets.UTF_8);
        String[] categories = {"business", "result-edit", "metadata", "transfer", "connection", "persistence"};
        for (String category : categories) {
            assertTrue(configuration.contains("name=\"com.dbstudio.sql." + category
                    + "\" level=\"INFO\" additivity=\"false\""));
            assertTrue(configuration.contains("<file>${LOG_DIR}/" + category + "-sql.log</file>"));
            assertTrue(configuration.contains("archive/" + category + "-sql.%d{yyyy-MM-dd}.%i.log.gz"));
        }
    }
}
