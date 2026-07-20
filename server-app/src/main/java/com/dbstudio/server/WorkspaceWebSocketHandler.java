package com.dbstudio.server;

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

@Component
public final class WorkspaceWebSocketHandler extends TextWebSocketHandler {
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
        Workspace workspace = registry.requireOwned(id, clientId);
        workspace.events().attach(session, clientId);
        registry.browserConnected(id, clientId);
        workspace.events().emit("workspace.ready", ApiPayloads.map("workspaceId", id, "clientId", clientId,
                "editors", workspace.editorRuntimeStates()));
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!"ping".equals(message.getPayload())) return;
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        Workspace workspace = registry.require(id);
        workspace.events().emitImmediate("workspace.pong", ApiPayloads.map("serverTime", System.currentTimeMillis()));
    }

    @Override protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        try { registry.require(id).events().pong(); } catch (ApiException ignored) { }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        try { registry.require(id).events().detach(session); } catch (ApiException ignored) { }
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    private void sendHeartbeats() {
        long now = System.currentTimeMillis();
        for (Workspace workspace : registry.openRuntimes()) {
            try {
                if (workspace.events().stale(now, 45_000L)) workspace.events().closeSession();
                else workspace.events().ping();
            } catch (Exception ignored) { workspace.events().closeSession(); }
        }
    }

    @PreDestroy public void close() { heartbeat.shutdownNow(); }
}
