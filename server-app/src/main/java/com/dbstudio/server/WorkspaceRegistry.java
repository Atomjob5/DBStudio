package com.dbstudio.server;

import com.dbstudio.desktop.AppDirectories;
import com.dbstudio.desktop.persistence.SettingsRepository;
import com.dbstudio.desktop.query.QueryRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@org.springframework.stereotype.Component
public final class WorkspaceRegistry implements AutoCloseable {
    static final long RECONNECT_SECONDS = 60L;
    private final Map<String, Workspace> workspaces = new ConcurrentHashMap<String, Workspace>();
    private final Map<String, ScheduledFuture<?>> expiry = new ConcurrentHashMap<String, ScheduledFuture<?>>();
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper mapper;
    private final SettingsRepository settings;
    private final EditorConnectionLimiter limiter;
    private volatile int idleTimeoutMinutes;

    public WorkspaceRegistry(ObjectMapper mapper, SettingsRepository settings, EditorConnectionLimiter limiter) {
        this.mapper = mapper;
        this.settings = settings;
        this.limiter = limiter;
        this.limiter.setMaximum(configuredMaxActiveSessions());
        this.idleTimeoutMinutes = configuredIdleTimeoutMinutes();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "dbstudio-workspace-expiry");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.scheduler.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { suspendIdleEditors(); }
        }, 1L, 1L, TimeUnit.MINUTES);
    }

    Workspace create(String id) {
        validateId(id);
        Workspace existing = workspaces.get(id);
        if (existing != null) { cancelExpiry(id); return existing; }
        Workspace created = new Workspace(id, configuredMaxRows(), configuredStreamBatchRows(), mapper,
                AppDirectories.dataDirectory().resolve("tmp").resolve(id), limiter);
        Workspace raced = workspaces.putIfAbsent(id, created);
        if (raced != null) { created.close(); return raced; }
        scheduleExpiry(id);
        return created;
    }

    Workspace require(String id) {
        validateId(id);
        Workspace workspace = workspaces.get(id);
        if (workspace == null) throw new ApiException("WORKSPACE_NOT_FOUND", "浏览器工作区不存在或已过期");
        return workspace;
    }

    void browserConnected(String id) { cancelExpiry(id); }
    void browserDisconnected(String id) { scheduleExpiry(id); }

    void setMaxRows(int maxRows) {
        int bounded = Math.max(1, Math.min(100_000, maxRows));
        for (Workspace workspace : workspaces.values()) workspace.editors().setMaxRows(bounded);
    }

    void setStreamBatchRows(int streamBatchRows) {
        int bounded = Math.max(1, Math.min(1_000, streamBatchRows));
        for (Workspace workspace : workspaces.values()) workspace.editors().setStreamBatchRows(bounded);
    }

    void setMaxActiveSessions(int maximum) { limiter.setMaximum(maximum); }
    void setIdleTimeoutMinutes(int minutes) { idleTimeoutMinutes = Math.max(1, Math.min(1_440, minutes)); }

    void broadcast(String type, Object payload) {
        for (Workspace workspace : workspaces.values()) workspace.events().emit(type, payload);
    }

    private void suspendIdleEditors() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(idleTimeoutMinutes);
        for (Workspace workspace : workspaces.values()) workspace.suspendIdle(cutoff);
    }

    private void scheduleExpiry(final String id) {
        cancelExpiry(id);
        ScheduledFuture<?> task = scheduler.schedule(new Runnable() {
            @Override public void run() {
                expiry.remove(id);
                Workspace workspace = workspaces.remove(id);
                if (workspace != null) workspace.close();
            }
        }, RECONNECT_SECONDS, TimeUnit.SECONDS);
        expiry.put(id, task);
    }

    private void cancelExpiry(String id) {
        ScheduledFuture<?> task = expiry.remove(id);
        if (task != null) task.cancel(false);
    }

    private int configuredMaxRows() {
        try {
            String raw = settings.get("result.maxRows").orElse(String.valueOf(QueryRunner.DEFAULT_MAX_ROWS));
            return Math.max(1, Math.min(100_000, Integer.parseInt(raw)));
        } catch (SQLException exception) {
            return QueryRunner.DEFAULT_MAX_ROWS;
        } catch (NumberFormatException exception) {
            return QueryRunner.DEFAULT_MAX_ROWS;
        }
    }

    private int configuredStreamBatchRows() {
        try {
            String raw = settings.get("result.streamBatchRows").orElse(
                    String.valueOf(QueryRunner.DEFAULT_STREAM_BATCH_ROWS));
            return Math.max(1, Math.min(1_000, Integer.parseInt(raw)));
        } catch (SQLException exception) {
            return QueryRunner.DEFAULT_STREAM_BATCH_ROWS;
        } catch (NumberFormatException exception) {
            return QueryRunner.DEFAULT_STREAM_BATCH_ROWS;
        }
    }

    private int configuredMaxActiveSessions() {
        try {
            return Math.max(1, Math.min(100, Integer.parseInt(
                    settings.get("connection.maxActiveSessions").orElse("10"))));
        } catch (Exception exception) { return 10; }
    }

    private int configuredIdleTimeoutMinutes() {
        try {
            return Math.max(1, Math.min(1_440, Integer.parseInt(
                    settings.get("connection.idleTimeoutMinutes").orElse("10"))));
        } catch (Exception exception) { return 10; }
    }

    private static void validateId(String id) {
        try { UUID.fromString(id); }
        catch (Exception exception) { throw new ApiException("INVALID_WORKSPACE_ID", "Workspace ID 无效"); }
    }

    @Override public void close() {
        scheduler.shutdownNow();
        for (ScheduledFuture<?> task : expiry.values()) task.cancel(false);
        expiry.clear();
        for (Workspace workspace : new ArrayList<Workspace>(workspaces.values())) workspace.close();
        workspaces.clear();
    }
}
