package com.dbstudio.spi;

import java.time.Duration;

public final class ConnectionTestResult {
    private final boolean success;
    private final String message;
    private final String serverVersion;
    private final Duration latency;

    public ConnectionTestResult(boolean success, String message, String serverVersion, Duration latency) {
        this.success = success;
        this.message = message;
        this.serverVersion = serverVersion;
        this.latency = latency;
    }

    public boolean success() { return success; }
    public String message() { return message; }
    public String serverVersion() { return serverVersion; }
    public Duration latency() { return latency; }

    public static ConnectionTestResult failure(String message, Duration latency) {
        return new ConnectionTestResult(false, message, "", latency);
    }
}
