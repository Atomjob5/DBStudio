package com.dbstudio.server;

import com.dbstudio.desktop.logging.SqlLogSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 本机 HTTP 安全与诊断过滤器。
 *
 * <p>所有请求先校验 Loopback Host/Origin 和可选本地令牌，再建立短生命周期 MDC。请求结束后
 * 自动恢复线程上下文，防止 Tomcat 线程复用时串联上一次请求的 Workspace 或 requestId。</p>
 */
@Component
public final class LocalRequestFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(LocalRequestFilter.class);
    public static final String REQUEST_ID = "dbstudio.requestId";
    private final LocalAccessToken token;
    private final ObjectMapper mapper;
    private final WorkspaceRegistry workspaces;

    public LocalRequestFilter(LocalAccessToken token, ObjectMapper mapper, WorkspaceRegistry workspaces) {
        this.token = token;
        this.mapper = mapper;
        this.workspaces = workspaces;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; "
                + "worker-src 'self' blob:; connect-src 'self' ws://127.0.0.1:*; object-src 'none'; "
                + "base-uri 'none'; frame-ancestors 'none'; form-action 'self'");

        String workspaceId = protectedWorkspaceId(request);
        String clientId = clientId(request);
        long started = System.nanoTime();
        try (LoggingContext ignored = LoggingContext.open(requestId, workspaceId, clientId, null, null)) {
            LOG.debug("HTTP请求开始 method={} path={}", request.getMethod(), request.getRequestURI());
            if (request.getRequestURI().startsWith("/api/v1")) {
                response.setHeader("Cache-Control", "no-store");
                if (!validHost(request) || !validOrigin(request)) {
                    LOG.warn("拒绝非本机HTTP请求 method={} path={}", request.getMethod(), request.getRequestURI());
                    reject(response, requestId, HttpServletResponse.SC_FORBIDDEN,
                            "LOCAL_ACCESS_REQUIRED", "请求来源不是当前 DBStudio 本地服务");
                    return;
                }
                boolean exchange = "/api/v1/auth/exchange".equals(request.getRequestURI());
                if (!exchange && !token.authenticated(request)) {
                    LOG.warn("拒绝未认证HTTP请求 method={} path={}", request.getMethod(), request.getRequestURI());
                    reject(response, requestId, HttpServletResponse.SC_UNAUTHORIZED,
                            "UNAUTHORIZED", "本地会话未认证或已经失效");
                    return;
                }
                if (workspaceId != null) {
                    try {
                        workspaces.requireOwned(workspaceId, clientId);
                    } catch (ApiException exception) {
                        String safeMessage = SqlLogSupport.sanitizeMessage(exception.getMessage());
                        LOG.warn("Workspace归属校验失败 code={} message={}", exception.getCode(), safeMessage);
                        reject(response, requestId, HttpServletResponse.SC_CONFLICT,
                                exception.getCode(), safeMessage);
                        return;
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            LOG.info("HTTP请求完成 method={} path={} status={} durationMs={}", request.getMethod(),
                    request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000L);
        }
    }

    private static String protectedWorkspaceId(HttpServletRequest request) {
        String prefix = "/api/v1/workspaces/";
        String path = request.getRequestURI();
        if (!path.startsWith(prefix)) return null;
        String remaining = path.substring(prefix.length());
        int separator = remaining.indexOf('/');
        if (separator < 0) return null;
        String operation = remaining.substring(separator + 1);
        if ("open".equals(operation)) return null;
        return remaining.substring(0, separator);
    }

    private static String clientId(HttpServletRequest request) {
        String header = request.getHeader("X-DBStudio-Client-Id");
        return header == null || header.trim().isEmpty() ? request.getParameter("clientId") : header;
    }

    private boolean validHost(HttpServletRequest request) {
        String host = request.getHeader("Host");
        return host != null && (host.equals("127.0.0.1:" + request.getLocalPort())
                || (request.getLocalPort() == 80 && host.equals("127.0.0.1")));
    }

    private boolean validOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isEmpty()) {
            return !"/api/v1/events".equals(request.getRequestURI());
        }
        return origin.equals("http://127.0.0.1:" + request.getLocalPort());
    }

    private void reject(HttpServletResponse response, String requestId, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiPayloads.map(
                "code", code, "message", message, "requestId", requestId));
    }
}
