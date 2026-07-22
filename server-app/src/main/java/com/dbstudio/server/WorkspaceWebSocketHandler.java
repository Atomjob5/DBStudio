package com.dbstudio.server;

import com.dbstudio.desktop.logging.SqlLogSupport;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workspace 事件通道的 WebSocket 入口。
 *
 * <p>服务端定时发送协议层 Ping，浏览器网络栈负责回复 Pong；因此页面进入后台时不依赖
 * JavaScript 定时器。收到关闭或传输异常后由事件通道摘除当前 Socket，避免旧连接回调误伤新连接。</p>
 */
@Component
public final class WorkspaceWebSocketHandler extends TextWebSocketHandler {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceWebSocketHandler.class);
    static final String WORKSPACE_ATTRIBUTE = "dbstudio.workspaceId";
    static final String CLIENT_ATTRIBUTE = "dbstudio.clientId";
    private final WorkspaceRegistry registry;
    private final ScheduledExecutorService heartbeat;

    public WorkspaceWebSocketHandler(WorkspaceRegistry registry) {
        this.registry = registry;
        this.heartbeat = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "dbstudio-websocket-heartbeat");
                thread.setDaemon(true); return thread;
            }
        });
        heartbeat.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { sendHeartbeats(); }
        }, 20L, 20L, TimeUnit.SECONDS);
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) {
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        String clientId = String.valueOf(session.getAttributes().get(CLIENT_ATTRIBUTE));
        try (LoggingContext ignored = LoggingContext.open(null, id, clientId, null, null)) {
            Workspace workspace = registry.requireOwned(id, clientId);
            workspace.events().attach(session, clientId);
            registry.browserConnected(id, clientId);
            LOG.info("WebSocket连接建立 workspaceId={} clientId={} session={} remote={}", id, clientId,
                    session.getId(), session.getRemoteAddress());
            workspace.events().emit("workspace.ready", ApiPayloads.map("workspaceId", id, "clientId", clientId,
                    "editors", workspace.editorRuntimeStates()));
        }
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!"ping".equals(message.getPayload())) return;
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        try (LoggingContext ignored = LoggingContext.open(null, id, null, null, null)) {
            Workspace workspace = registry.require(id);
            workspace.events().emitImmediate("workspace.pong", ApiPayloads.map("serverTime", System.currentTimeMillis()));
        }
    }

    @Override protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        try (LoggingContext context = LoggingContext.open(null, id,
                String.valueOf(session.getAttributes().get(CLIENT_ATTRIBUTE)), null, null)) {
            try { registry.require(id).events().pong(); }
            catch (ApiException ignored) { LOG.debug("忽略已关闭Workspace的Pong workspaceId={}", id); }
        }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        try (LoggingContext ignored = LoggingContext.open(null, id,
                String.valueOf(session.getAttributes().get(CLIENT_ATTRIBUTE)), null, null)) {
            try {
                boolean detached = registry.require(id).events().detach(session);
                LOG.info("WebSocket连接关闭 workspaceId={} session={} status={} detached={}", id,
                        session.getId(), status, detached);
            } catch (ApiException ignoredException) {
                LOG.debug("关闭已不存在Workspace的WebSocket workspaceId={}", id);
            }
        }
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        try (LoggingContext ignored = LoggingContext.open(null, id,
                String.valueOf(session.getAttributes().get(CLIENT_ATTRIBUTE)), null, null)) {
            LOG.warn("WebSocket传输异常 session={} message={}", session.getId(),
                    SqlLogSupport.sanitizeMessage(exception.getMessage()), exception);
            if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        for (Workspace workspace : registry.openRuntimes()) {
            try (LoggingContext ignored = LoggingContext.open(null, workspace.id(),
                    workspace.events().connectedClientId(), null, null)) {
                if (workspace.events().stale(now, 45_000L)) {
                    LOG.warn("WebSocket心跳超时 workspaceId={}，主动关闭连接", workspace.id());
                    workspace.events().closeSession();
                } else workspace.events().ping();
            } catch (Exception exception) {
                LOG.warn("WebSocket心跳发送失败 workspaceId={}", workspace.id(), exception);
                workspace.events().closeSession();
            }
        }
    }

    @PreDestroy public void close() { heartbeat.shutdownNow(); }
}
