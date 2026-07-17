package com.dbstudio.server;

import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException exception, HttpServletRequest request) {
        HttpStatus status = "UNAUTHORIZED".equals(exception.getCode()) ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiPayloads.map("code", exception.getCode(),
                "message", exception.getMessage(), "details", exception.getDetails(),
                "requestId", request.getAttribute(LocalRequestFilter.REQUEST_ID)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception, HttpServletRequest request) {
        String requestId = String.valueOf(request.getAttribute(LocalRequestFilter.REQUEST_ID));
        LOG.error("DBStudio request {} failed", requestId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiPayloads.map(
                "code", "INTERNAL_ERROR", "message", "操作失败，请查看本地日志中的请求编号",
                "requestId", requestId));
    }
}
