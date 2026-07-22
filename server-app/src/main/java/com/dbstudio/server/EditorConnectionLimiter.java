package com.dbstudio.server;

import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程级物理编辑器 JDBC 会话准入控制器。
 *
 * <p>逻辑编辑器可以很多，但真正打开的 JDBC 会话受全局上限约束。达到上限时只驱逐
 * 可安全暂停的最久未使用会话，事务固定或正在执行的会话不会被强制关闭。</p>
 */
@org.springframework.stereotype.Component
public final class EditorConnectionLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(EditorConnectionLimiter.class);
    private final Map<String, ActiveSession> active = new HashMap<String, ActiveSession>();
    private int maximum = 10;

    public synchronized void setMaximum(int value) {
        maximum = Math.max(1, Math.min(100, value));
        LOG.info("设置物理编辑器JDBC会话上限 maximum={}", maximum);
    }
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
        if (active.size() >= maximum) {
            LOG.warn("物理编辑器JDBC会话达到上限 key={} active={} maximum={}", key, active.size(), maximum);
            return false;
        }
        active.put(key, new ActiveSession(control, suspendedCallback));
        LOG.debug("申请物理编辑器JDBC会话 key={} active={}", key, active.size());
        return true;
    }

    public synchronized void touch(String key) {
        ActiveSession value = active.get(key);
        if (value != null) value.touched = System.currentTimeMillis();
    }

    public synchronized void release(String key) {
        if (active.remove(key) != null) LOG.debug("释放物理编辑器JDBC会话 key={} active={}", key, active.size());
    }

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
        if (candidate == null || !candidate.control.suspend()) {
            LOG.debug("没有可安全暂停的物理JDBC会话 active={} maximum={}", active.size(), maximum);
            return;
        }
        active.remove(candidateKey);
        LOG.info("为新会话暂停最久未使用JDBC会话 key={} active={}", candidateKey, active.size());
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
