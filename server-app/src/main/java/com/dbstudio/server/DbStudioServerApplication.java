package com.dbstudio.server;

import com.dbstudio.desktop.AppDirectories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** DBStudio 本地 Spring Boot 入口，负责在框架初始化前准备平台日志目录。 */
@SpringBootApplication
public class DbStudioServerApplication {
    /* LoggerFactory 可能在静态字段初始化时触发 Logback 配置，因此必须先设置平台日志目录。 */
    static { initializeLoggingDirectory(); }
    private static final Logger LOG = LoggerFactory.getLogger(DbStudioServerApplication.class);
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(DbStudioServerApplication.class);
        application.setRegisterShutdownHook(true);
        ConfigurableApplicationContext context = application.run(args);
        // 核心模块不依赖 Spring，通过系统属性把 application.properties 的 SQL 日志策略传给它。
        synchronizeSqlLogProperties(context);
        int port = context instanceof ServletWebServerApplicationContext
                ? ((ServletWebServerApplicationContext) context).getWebServer().getPort() : -1;
        LOG.info("DBStudio服务启动 address=127.0.0.1 port={} dataDirectory={} logDirectory={}", port,
                AppDirectories.dataDirectory(), System.getProperty("dbstudio.log.dir"));
    }

    private static void initializeLoggingDirectory() {
        String configured = System.getProperty("dbstudio.log.dir");
        Path directory = configured == null || configured.trim().isEmpty()
                ? AppDirectories.dataDirectory().resolve("logs") : java.nio.file.Paths.get(configured);
        System.setProperty("dbstudio.log.dir", directory.toString());
        try {
            // 预先创建当前日志目录和归档目录，避免首次滚动时由不同平台的 Logback 实现决定行为。
            Files.createDirectories(directory.resolve("archive"));
        }
        catch (IOException exception) {
            System.err.println("无法创建DBStudio日志目录：" + directory + "，将使用Logback降级路径");
        }
    }

    private static void synchronizeSqlLogProperties(ConfigurableApplicationContext context) {
        String mode = context.getEnvironment().getProperty("dbstudio.logging.sql.mode");
        String length = context.getEnvironment().getProperty("dbstudio.logging.sql.preview-length");
        if (mode != null && !mode.trim().isEmpty()) System.setProperty("dbstudio.logging.sql.mode", mode);
        if (length != null && !length.trim().isEmpty()) {
            System.setProperty("dbstudio.logging.sql.preview-length", length);
        }
    }
}
