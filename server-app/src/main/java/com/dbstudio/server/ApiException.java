package com.dbstudio.server;

public final class ApiException extends RuntimeException {
    private final String code;
    private final Object details;

    public ApiException(String code, String message) { this(code, message, null, null); }
    public ApiException(String code, String message, Throwable cause) { this(code, message, null, cause); }
    public ApiException(String code, String message, Object details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }
    public String getCode() { return code; }
    public Object getDetails() { return details; }
}
