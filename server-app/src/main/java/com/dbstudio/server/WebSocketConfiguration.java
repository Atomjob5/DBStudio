package com.dbstudio.server;

import java.util.List;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final WorkspaceWebSocketHandler handler;
    private final WorkspaceRegistry workspaces;
    private final LocalAccessToken token;

    public WebSocketConfiguration(WorkspaceWebSocketHandler handler, WorkspaceRegistry workspaces,
                                  LocalAccessToken token) {
        this.handler = handler;
        this.workspaces = workspaces;
        this.token = token;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/events")
                .addInterceptors(new LocalHandshakeInterceptor(workspaces, token))
                .setAllowedOriginPatterns("http://127.0.0.1:*");
    }

    private static final class LocalHandshakeInterceptor implements HandshakeInterceptor {
        private final WorkspaceRegistry workspaces;
        private final LocalAccessToken token;
        private LocalHandshakeInterceptor(WorkspaceRegistry workspaces, LocalAccessToken token) {
            this.workspaces = workspaces; this.token = token;
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler handler, Map<String, Object> attributes) {
            if (!(request instanceof ServletServerHttpRequest)) return false;
            HttpServletRequest servlet = ((ServletServerHttpRequest) request).getServletRequest();
            if (!token.authenticated(servlet)) return false;
            List<String> ids = UriComponentsBuilder.fromUri(request.getURI()).build()
                    .getQueryParams().get("workspaceId");
            if (ids == null || ids.size() != 1) return false;
            try {
                workspaces.require(ids.get(0));
                attributes.put(WorkspaceWebSocketHandler.WORKSPACE_ATTRIBUTE, ids.get(0));
                return true;
            } catch (ApiException exception) {
                return false;
            }
        }

        @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                             WebSocketHandler handler, Exception exception) { }
    }
}
