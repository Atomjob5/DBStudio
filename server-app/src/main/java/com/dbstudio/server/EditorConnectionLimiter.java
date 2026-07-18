package com.dbstudio.server;

import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import java.util.HashMap;
import java.util.Map;

/** Process-wide admission controller for physical editor JDBC sessions. */
@org.springframework.stereotype.Component
public final class EditorConnectionLimiter {
    private final Map<String, ActiveSession> active = new HashMap<String, ActiveSession>();
    private int maximum = 10;

    public synchronized void setMaximum(int value) { maximum = Math.max(1, Math.min(100, value)); }
    public synchronized int maximum() { return maximum; }
    public synchronized int activeCount() { return active.size(); }

    public synchronized boolean acquire(String key, EditorSession editor, Runnable suspendedCallback) {
        return acquire(key, new SessionControl() {
            @Override public boolean canSuspend() { return editor.canSuspend(); }
            @Override public boolean suspend() { return editor.suspend(); }
            @Override public long lastTouched() { return editor.lastTouched(); }
        }, suspendedCallback);
    }

    synchronized boolean acquire(String key, SessionControl control, Runnable suspendedCallback) {
        ActiveSession existing = active.get(key);
        if (existing != null) { existing.touched = System.currentTimeMillis(); return true; }
        if (active.size() >= maximum) evictOldestSafe();
        if (active.size() >= maximum) return false;
        active.put(key, new ActiveSession(control, suspendedCallback));
        return true;
    }

    public synchronized void touch(String key) {
        ActiveSession value = active.get(key);
        if (value != null) value.touched = System.currentTimeMillis();
    }

    public synchronized void release(String key) { active.remove(key); }

    private void evictOldestSafe() {
        String candidateKey = null;
        ActiveSession candidate = null;
        for (Map.Entry<String, ActiveSession> entry : active.entrySet()) {
            ActiveSession value = entry.getValue();
            if (!value.control.canSuspend()) continue;
            if (candidate == null || value.control.lastTouched() < candidate.control.lastTouched()) {
                candidateKey = entry.getKey(); candidate = value;
            }
        }
        if (candidate == null || !candidate.control.suspend()) return;
        active.remove(candidateKey);
        try { candidate.suspendedCallback.run(); } catch (RuntimeException ignored) { }
    }

    interface SessionControl {
        boolean canSuspend();
        boolean suspend();
        long lastTouched();
    }

    private static final class ActiveSession {
        private final SessionControl control;
        private final Runnable suspendedCallback;
        private long touched = System.currentTimeMillis();
        private ActiveSession(SessionControl control, Runnable suspendedCallback) {
            this.control = control; this.suspendedCallback = suspendedCallback;
        }
    }
}
