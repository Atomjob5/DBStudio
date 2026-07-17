package com.dbstudio.desktop.security;

public final class SecretStoreException extends Exception {
    public SecretStoreException(String message) {
        super(message);
    }

    public SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
