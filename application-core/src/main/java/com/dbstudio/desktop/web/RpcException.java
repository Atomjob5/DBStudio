package com.dbstudio.desktop.web;

public final class RpcException extends RuntimeException {
    private final String code;
    private final Object details;

    public RpcException(String code, String message) {
        this(code, message, null, null);
    }

    public RpcException(String code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    public RpcException(String code, String message, Object details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
