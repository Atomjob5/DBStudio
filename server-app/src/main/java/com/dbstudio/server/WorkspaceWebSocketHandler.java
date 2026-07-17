package com.dbstudio.server;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public final class WorkspaceWebSocketHandler extends TextWebSocketHandler {
    static final String WORKSPACE_ATTRIBUTE = "dbstudio.workspaceId";
    private final WorkspaceRegistry registry;
    public WorkspaceWebSocketHandler(WorkspaceRegistry registry) { this.registry = registry; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        Workspace workspace = registry.require(id);
        workspace.events().attach(session);
        registry.browserConnected(id);
        workspace.events().emit("workspace.ready", ApiPayloads.map("workspaceId", id));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // The browser currently sends only optional keep-alive messages. Database commands use REST.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String id = String.valueOf(session.getAttributes().get(WORKSPACE_ATTRIBUTE));
        try {
            Workspace workspace = registry.require(id);
            if (workspace.events().detach(session)) registry.browserDisconnected(id);
        } catch (ApiException ignored) { }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }
}
