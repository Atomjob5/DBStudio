package com.dbstudio.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class LocalRequestFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID = "dbstudio.requestId";
    private final LocalAccessToken token;
    private final ObjectMapper mapper;

    public LocalRequestFilter(LocalAccessToken token, ObjectMapper mapper) {
        this.token = token;
        this.mapper = mapper;
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

        if (request.getRequestURI().startsWith("/api/v1")) {
            response.setHeader("Cache-Control", "no-store");
            if (!validHost(request) || !validOrigin(request)) {
                reject(response, requestId, HttpServletResponse.SC_FORBIDDEN,
                        "LOCAL_ACCESS_REQUIRED", "请求来源不是当前 DBStudio 本地服务");
                return;
            }
            boolean exchange = "/api/v1/auth/exchange".equals(request.getRequestURI());
            if (!exchange && !token.authenticated(request)) {
                reject(response, requestId, HttpServletResponse.SC_UNAUTHORIZED,
                        "UNAUTHORIZED", "本地会话未认证或已经失效");
                return;
            }
        }
        chain.doFilter(request, response);
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
