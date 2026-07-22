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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 注册本机 Workspace WebSocket，并在握手阶段校验 Workspace、客户端归属和可选访问令牌。
 * 业务事件不直接在这里处理，建立连接后统一交给 {@link WorkspaceWebSocketHandler}。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketConfiguration.class);
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
            if (!(request instanceof ServletServerHttpRequest)) {
                LOG.warn("拒绝非Servlet WebSocket握手");
                return false;
            }
            HttpServletRequest servlet = ((ServletServerHttpRequest) request).getServletRequest();
            if (!token.authenticated(servlet)) {
                LOG.warn("拒绝未认证WebSocket握手 path={}", request.getURI().getPath());
                return false;
            }
            List<String> ids = UriComponentsBuilder.fromUri(request.getURI()).build()
                    .getQueryParams().get("workspaceId");
            List<String> clients = UriComponentsBuilder.fromUri(request.getURI()).build()
                    .getQueryParams().get("clientId");
            if (ids == null || ids.size() != 1 || clients == null || clients.size() != 1) {
                LOG.warn("拒绝缺少Workspace或clientId的WebSocket握手");
                return false;
            }
            try {
                workspaces.requireOwned(ids.get(0), clients.get(0));
                attributes.put(WorkspaceWebSocketHandler.WORKSPACE_ATTRIBUTE, ids.get(0));
                attributes.put(WorkspaceWebSocketHandler.CLIENT_ATTRIBUTE, clients.get(0));
                return true;
            } catch (ApiException exception) {
                LOG.warn("拒绝WebSocket握手 workspaceId={} clientId={} code={}", ids.get(0), clients.get(0),
                        exception.getCode());
                return false;
            }
        }

        @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                             WebSocketHandler handler, Exception exception) { }
    }
}
