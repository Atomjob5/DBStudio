package com.dbstudio.server;

import java.util.Map;
import org.slf4j.MDC;

/**
 * DBStudio 诊断上下文的短生命周期封装。
 *
 * <p>查询和 WebSocket 回调运行在线程池中，不能依赖请求线程自动继承 MDC。
 * 使用此类建立上下文后必须关闭，以防线程复用时把上一个请求的标识带入下一个任务。</p>
 */
final class LoggingContext implements AutoCloseable {
    private final Map<String, String> previous;

    private LoggingContext(Map<String, String> previous) { this.previous = previous; }

    static LoggingContext open(String requestId, String workspaceId, String clientId,
                               String editorId, String executionId) {
        LoggingContext context = new LoggingContext(MDC.getCopyOfContextMap());
        put("requestId", requestId);
        put("workspaceId", workspaceId);
        put("clientId", clientId);
        put("editorId", editorId);
        put("executionId", executionId);
        return context;
    }

    private static void put(String key, String value) {
        if (value == null || value.trim().isEmpty()) MDC.remove(key);
        else MDC.put(key, value);
    }

    @Override public void close() {
        MDC.clear();
        if (previous != null) MDC.setContextMap(previous);
    }
}
