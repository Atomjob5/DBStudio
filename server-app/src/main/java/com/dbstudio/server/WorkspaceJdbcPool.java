package com.dbstudio.server;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.logging.SqlLogSupport;
import com.dbstudio.spi.DatabaseSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workspace 范围内的 JDBC 队列。
 *
 * <p>非事务查询借用空闲连接，执行结束后重置并归还；事务或锁定查询会通过
 * {@code pinnedEditorId} 固定连接。断连退休只递增代次，不复用旧代次连接，避免半开
 * JDBC 被下一次 SQL 误用。</p>
 */
final class WorkspaceJdbcPool implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceJdbcPool.class);
    private final String key;
    private final DatabaseContext context;
    private final EditorConnectionLimiter limiter;
    private final Listener listener;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final ExecutorService maintenance;
    private final ScheduledExecutorService watchdog;
    private boolean autoCommit;
    private long generation;
    private boolean closed;

    WorkspaceJdbcPool(String key, DatabaseContext context, EditorConnectionLimiter limiter, boolean autoCommit) {
        this(key, context, limiter, autoCommit, Listener.NONE);
    }

    WorkspaceJdbcPool(String key, DatabaseContext context, EditorConnectionLimiter limiter, boolean autoCommit,
                      Listener listener) {
        this.key = key; this.context = context; this.limiter = limiter; this.autoCommit = autoCommit;
        this.listener = listener == null ? Listener.NONE : listener;
        this.maintenance = Executors.newCachedThreadPool(daemonFactory("dbstudio-jdbc-maintenance"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(daemonFactory("dbstudio-jdbc-watchdog"));
    }

    DatabaseContext context() { return context; }

    synchronized Lease borrow() throws SQLException {
        ensureOpen();
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.closed) { iterator.remove(); continue; }
            if (entry.generation != generation || entry.retired || entry.borrowed
                    || entry.pinnedEditorId != null || entry.maintenance != Maintenance.NONE) continue;
            if (!valid(entry.session)) { closeEntry(entry, "JDBC 连接已经失效", true); iterator.remove(); continue; }
            entry.borrowed = true; entry.lastUsed = System.currentTimeMillis(); limiter.touch(entry.permitKey);
            changed(entry);
            LOG.debug("借用JDBC连接 pool={} generation={} physicalCount={}", key, generation, entries.size());
            return new Lease(this, entry);
        }

        final Entry created = new Entry(generation, key + ":" + UUID.randomUUID().toString());
        if (!limiter.acquire(created.permitKey, created, new Runnable() {
            @Override public void run() { /* Entry 控制对象已负责关闭对应 JDBC 会话。 */ }
        })) throw new ApiException("CONNECTION_LIMIT_REACHED", "已达到最大活动链接数，请处理事务或等待空闲链接回收");
        created.slotId = limiter.slotId(created.permitKey);
        try {
            created.session = context.openEditorSession();
            applyAutoCommit(created.session);
            created.borrowed = true;
            created.lastUsed = System.currentTimeMillis();
            entries.add(created);
            limiter.attachConnection(created.permitKey, created.connectionId);
            changed(created);
            LOG.info("创建JDBC连接 pool={} generation={} physicalCount={}", key, generation, entries.size());
            return new Lease(this, created);
        } catch (SQLException exception) {
            if (created.session != null) {
                try { created.session.close(); }
                catch (SQLException closeFailure) { exception.addSuppressed(closeFailure); }
                created.session = null;
            }
            limiter.release(created.permitKey);
            LOG.warn("创建JDBC连接失败 pool={} generation={} message={}", key, generation,
                    SqlLogSupport.sanitizeMessage(exception.getMessage()));
            throw exception;
        }
    }

    synchronized Lease pinned(String editorId) {
        for (Entry entry : entries) {
            if (!entry.closed && editorId.equals(entry.pinnedEditorId)) {
                entry.borrowed = true; entry.lastUsed = System.currentTimeMillis(); limiter.touch(entry.permitKey);
                entry.editorId = editorId; changed(entry);
                LOG.debug("借用事务固定JDBC连接 pool={} editorId={}", key, editorId);
                return new Lease(this, entry);
            }
        }
        return null;
    }

    synchronized void pin(String editorId, Lease lease) {
        Entry entry = owned(lease);
        entry.pinnedEditorId = editorId; entry.editorId = editorId;
        entry.borrowed = false;
        entry.lastUsed = System.currentTimeMillis();
        changed(entry);
        LOG.info("固定JDBC连接到事务编辑器 pool={} editorId={}", key, editorId);
    }

    synchronized void release(Lease lease) {
        Entry entry = owned(lease);
        if (entry.pinnedEditorId != null) {
            entry.borrowed = false; entry.lastUsed = System.currentTimeMillis();
            changed(entry);
            LOG.debug("释放事务固定JDBC借用 pool={} editorId={}", key, entry.pinnedEditorId);
            return;
        }
        if (!reset(entry.session)) { closeAndRemove(entry, "重置 JDBC 会话失败", true); return; }
        entry.borrowed = false; entry.editorId = null; entry.lastUsed = System.currentTimeMillis();
        changed(entry);
        LOG.debug("归还JDBC连接 pool={} physicalCount={}", key, entries.size());
    }

    synchronized void unpinAndRelease(String editorId) {
        Entry entry = findPinned(editorId);
        if (entry == null) return;
        entry.pinnedEditorId = null; entry.editorId = null;
        if (!reset(entry.session)) { closeAndRemove(entry, "重置 JDBC 会话失败", true); return; }
        entry.borrowed = false; entry.lastUsed = System.currentTimeMillis(); changed(entry);
        LOG.info("解除事务固定并归还JDBC连接 pool={} editorId={}", key, editorId);
    }

    synchronized void closePinned(String editorId) {
        Entry entry = findPinned(editorId);
        if (entry != null) closeAndRemove(entry, "事务连接已关闭", true);
    }

    synchronized void associate(String editorId, Lease lease) {
        Entry entry = owned(lease);
        entry.editorId = editorId; changed(entry);
    }

    synchronized boolean hasPinned(String editorId) { return findPinned(editorId) != null; }

    synchronized int pinnedCount() {
        int count = 0;
        for (Entry entry : entries) if (!entry.closed && entry.pinnedEditorId != null) count++;
        return count;
    }

    synchronized void retireUnpinned() {
        long now = System.currentTimeMillis();
        generation++;
        LOG.warn("退休非事务JDBC连接代次 pool={} newGeneration={}", key, generation);
        for (Entry entry : entries) {
            if (entry.closed || entry.pinnedEditorId != null || entry.maintenance != Maintenance.NONE) continue;
            entry.retired = true; entry.retiredAt = now;
            entry.message = "连接已退休，等待回收"; changed(entry);
        }
    }

    synchronized void reap(long cutoffMillis) {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.closed) { iterator.remove(); continue; }
            if (entry.borrowed || entry.pinnedEditorId != null || entry.maintenance != Maintenance.NONE) continue;
            long since = entry.retired ? entry.retiredAt : entry.lastUsed;
            if (since <= cutoffMillis) {
                LOG.info("回收空闲JDBC连接 pool={} generation={} retired={}", key, entry.generation, entry.retired);
                closeEntry(entry, "空闲连接已回收", true); iterator.remove();
            }
        }
        if (entries.isEmpty()) context.suspendMetadata();
    }

    synchronized int physicalCount() {
        int count = 0;
        for (Entry entry : entries) if (!entry.closed) count++;
        return count;
    }

    synchronized void setAutoCommit(boolean enabled) {
        if (autoCommit == enabled) return;
        for (Entry entry : entries) {
            if (!entry.closed && (entry.borrowed || entry.pinnedEditorId != null
                    || entry.maintenance != Maintenance.NONE)) {
                throw new IllegalStateException("Cannot change auto-commit while a JDBC lease is active");
            }
        }
        autoCommit = enabled;
        for (Entry entry : new ArrayList<Entry>(entries)) closeEntry(entry, "连接池配置已更新", true);
        entries.clear();
        LOG.info("更新连接池自动提交模式 pool={} autoCommit={}", key, enabled);
    }

    private Entry findPinned(String editorId) {
        for (Entry entry : entries) if (!entry.closed && editorId.equals(entry.pinnedEditorId)) return entry;
        return null;
    }

    private Entry owned(Lease lease) {
        if (lease == null || lease.pool != this || lease.entry.closed) {
            throw new IllegalStateException("JDBC lease is not active");
        }
        return lease.entry;
    }

    synchronized List<Snapshot> snapshots() {
        List<Snapshot> result = new ArrayList<Snapshot>();
        for (Entry entry : entries) if (!entry.closed) result.add(snapshot(entry));
        return result;
    }

    synchronized Snapshot snapshot(String connectionId) {
        Entry entry = find(connectionId);
        return entry == null ? null : snapshot(entry);
    }

    CompletableFuture<Snapshot> probe(String connectionId, long expectedVersion) {
        final Entry entry;
        final long operationVersion;
        synchronized (this) {
            entry = require(connectionId);
            requireVersion(entry, expectedVersion);
            if (entry.borrowed || entry.pinnedEditorId != null || entry.retired
                    || entry.maintenance != Maintenance.NONE) {
                throw new ApiException("JDBC_CONNECTION_NOT_IDLE", "只有空闲且未固定事务的连接可以探活");
            }
            entry.maintenance = Maintenance.PROBING;
            entry.message = "正在探活";
            changed(entry); operationVersion = entry.stateVersion;
        }
        final CompletableFuture<Snapshot> response = new CompletableFuture<Snapshot>();
        maintenance.execute(new Runnable() {
            @Override public void run() {
                long started = System.nanoTime(); boolean healthy = false; Throwable failure = null;
                try { healthy = !entry.session.isClosed() && entry.session.jdbcConnection().isValid(2); }
                catch (Throwable error) { failure = error; }
                Snapshot completed = completeProbe(entry, operationVersion, healthy,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), failure);
                if (completed != null) response.complete(completed);
            }
        });
        watchdog.schedule(new Runnable() {
            @Override public void run() {
                Snapshot timeout = markProbeTimeout(entry, operationVersion);
                if (timeout != null) response.complete(timeout);
            }
        }, 3, TimeUnit.SECONDS);
        return response;
    }

    Snapshot abort(String connectionId, long expectedVersion) {
        final Entry entry;
        final DatabaseSession session;
        final Snapshot aborting;
        synchronized (this) {
            entry = require(connectionId);
            requireVersion(entry, expectedVersion);
            if (entry.maintenance == Maintenance.ABORTING) {
                throw new ApiException("JDBC_CONNECTION_ABORTING", "连接正在强制断开");
            }
            entry.maintenance = Maintenance.ABORTING; entry.message = "正在强制断开";
            entry.lastUsed = System.currentTimeMillis();
            changed(entry); aborting = snapshot(entry);
            entry.closed = true; entry.borrowed = false; entry.pinnedEditorId = null;
            entries.remove(entry); session = entry.session; entry.session = null;
            limiter.release(entry.permitKey);
        }
        listener.terminal(aborting);
        listener.changed();
        maintenance.execute(new Runnable() {
            @Override public void run() {
                String message = "连接已强制断开"; boolean failed = false;
                try {
                    if (session != null) session.jdbcConnection().abort(maintenance);
                } catch (Throwable error) {
                    failed = true; message = "强制断开失败：" + sanitize(error.getMessage());
                    if (session != null) try { session.close(); }
                    catch (SQLException closeFailure) { LOG.debug("abort失败后的关闭也失败", closeFailure); }
                }
                Snapshot terminal = terminal(entry, failed ? State.ERROR : State.DISCONNECTED, message);
                listener.terminal(terminal); listener.changed();
                LOG.info("JDBC连接强制断开完成 pool={} connectionId={} failed={}", key, entry.connectionId, failed);
            }
        });
        return aborting;
    }

    private synchronized Snapshot completeProbe(Entry entry, long operationVersion, boolean healthy,
                                                long latencyMs, Throwable failure) {
        if (entry.closed || entry.stateVersion != operationVersion
                || (entry.maintenance != Maintenance.PROBING && entry.maintenance != Maintenance.UNRESPONSIVE)) {
            return null;
        }
        if (healthy) {
            entry.maintenance = Maintenance.NONE; entry.lastProbeLatencyMs = latencyMs;
            entry.lastUsed = System.currentTimeMillis(); entry.message = "探活成功"; changed(entry);
            return snapshot(entry);
        }
        String reason = failure == null ? "探活失败，连接不可用" : "探活失败：" + sanitize(failure.getMessage());
        Snapshot disconnected = terminal(entry, State.DISCONNECTED, reason);
        closeAndRemove(entry, reason, false);
        listener.terminal(disconnected); listener.changed();
        return disconnected;
    }

    private synchronized Snapshot markProbeTimeout(Entry entry, long operationVersion) {
        if (entry.closed || entry.stateVersion != operationVersion || entry.maintenance != Maintenance.PROBING) return null;
        entry.maintenance = Maintenance.UNRESPONSIVE; entry.message = "探活超过 3 秒仍未响应"; changed(entry);
        return snapshot(entry);
    }

    private Entry find(String connectionId) {
        for (Entry entry : entries) if (!entry.closed && entry.connectionId.equals(connectionId)) return entry;
        return null;
    }

    private Entry require(String connectionId) {
        Entry entry = find(connectionId);
        if (entry == null) throw new ApiException("JDBC_CONNECTION_NOT_FOUND", "JDBC 连接不存在或已经断开");
        return entry;
    }

    private static void requireVersion(Entry entry, long expectedVersion) {
        if (expectedVersion != entry.stateVersion) {
            throw new ApiException("JDBC_CONNECTION_STATE_CHANGED", "连接状态已经变化，请刷新后重新确认");
        }
    }

    private void closeAndRemove(Entry entry, String message, boolean terminal) {
        closeEntry(entry, message, terminal); entries.remove(entry);
    }

    private void closeEntry(Entry entry, String message, boolean recordTerminal) {
        if (entry.closed) return;
        entry.closed = true; entry.borrowed = false;
        DatabaseSession session = entry.session; entry.session = null;
        if (session != null) try { session.close(); }
        catch (SQLException exception) { LOG.warn("关闭JDBC连接失败 pool={} generation={}", key, entry.generation, exception); }
        limiter.release(entry.permitKey);
        if (recordTerminal) listener.terminal(terminal(entry, State.DISCONNECTED, message));
        listener.changed();
    }

    private boolean reset(DatabaseSession session) {
        try {
            Connection connection = session.jdbcConnection();
            if (connection.getAutoCommit()) connection.setAutoCommit(false);
            context.provider().connections().resetSession(session, context.profile());
            applyAutoCommit(session);
            return true;
        } catch (SQLException exception) {
            LOG.warn("重置JDBC会话失败 pool={} message={}", key, SqlLogSupport.sanitizeMessage(exception.getMessage()));
            return false;
        }
    }

    private void applyAutoCommit(DatabaseSession session) throws SQLException {
        Connection connection = session.jdbcConnection();
        if (connection.getAutoCommit() != autoCommit) connection.setAutoCommit(autoCommit);
    }

    private static boolean valid(DatabaseSession session) {
        if (session == null) return false;
        try { return !session.isClosed() && session.jdbcConnection().isValid(2); }
        catch (SQLException exception) {
            LOG.debug("JDBC健康检查失败", exception);
            return false;
        }
    }

    private void ensureOpen() throws SQLException {
        if (closed) throw new SQLException("Workspace JDBC pool is closed");
    }

    @Override public synchronized void close() {
        closed = true;
        for (Entry entry : new ArrayList<Entry>(entries)) closeEntry(entry, "连接池已关闭", false);
        entries.clear(); context.close();
        maintenance.shutdownNow(); watchdog.shutdownNow();
        LOG.info("关闭JDBC连接池 pool={}", key);
    }

    static final class Lease {
        private final WorkspaceJdbcPool pool;
        private final Entry entry;
        private Lease(WorkspaceJdbcPool pool, Entry entry) { this.pool = pool; this.entry = entry; }
        DatabaseSession session() { return entry.session; }
        WorkspaceJdbcPool pool() { return pool; }
        String connectionId() { return entry.connectionId; }
        String slotId() { return entry.slotId; }
    }

    private final class Entry implements EditorConnectionLimiter.SessionControl {
        private final long generation;
        private final String permitKey;
        private final String connectionId = UUID.randomUUID().toString();
        private final long createdAt = System.currentTimeMillis();
        private DatabaseSession session;
        private boolean borrowed, retired, closed;
        private String slotId, pinnedEditorId, editorId, message;
        private Maintenance maintenance = Maintenance.NONE;
        private long stateVersion = 1L;
        private Long lastProbeLatencyMs;
        private long lastUsed = System.currentTimeMillis(), retiredAt;
        private Entry(long generation, String permitKey) { this.generation=generation; this.permitKey=permitKey; }
        @Override public boolean canSuspend() {
            return !borrowed && pinnedEditorId == null && !closed && maintenance == Maintenance.NONE;
        }
        @Override public boolean suspend() {
            synchronized (WorkspaceJdbcPool.this) {
                if (!canSuspend()) return false;
                closeEntry(this, "连接已由活动会话上限回收", true); entries.remove(this); return true;
            }
        }
        @Override public long lastTouched() { return retired ? retiredAt : lastUsed; }
    }

    enum State { IDLE, BUSY, TRANSACTION, PROBING, UNRESPONSIVE, ABORTING, DISCONNECTED, ERROR }
    private enum Maintenance { NONE, PROBING, UNRESPONSIVE, ABORTING }

    static final class Snapshot {
        final String slotId, connectionId, profileId, profileName, providerId, databaseName, schemaName;
        final String state, editorId, message;
        final long stateVersion, createdAt, lastActiveAt;
        final Long lastProbeLatencyMs, disconnectedAt;
        Snapshot(String slotId, String connectionId, long stateVersion, String profileId, String profileName,
                 String providerId, String databaseName, String schemaName, State state, String editorId,
                 long createdAt, long lastActiveAt,
                 Long lastProbeLatencyMs, Long disconnectedAt, String message) {
            this.slotId=slotId; this.connectionId=connectionId; this.stateVersion=stateVersion; this.profileId=profileId;
            this.profileName=profileName; this.providerId=providerId; this.databaseName=databaseName;
            this.schemaName=schemaName; this.state=state.name().toLowerCase();
            this.editorId=editorId; this.createdAt=createdAt; this.lastActiveAt=lastActiveAt;
            this.lastProbeLatencyMs=lastProbeLatencyMs; this.disconnectedAt=disconnectedAt; this.message=message;
        }
    }

    interface Listener {
        Listener NONE = new Listener() {
            @Override public void changed() { }
            @Override public void terminal(Snapshot snapshot) { }
        };
        void changed();
        void terminal(Snapshot snapshot);
    }

    private Snapshot snapshot(Entry entry) {
        State state = entry.maintenance == Maintenance.PROBING ? State.PROBING
                : entry.maintenance == Maintenance.UNRESPONSIVE ? State.UNRESPONSIVE
                : entry.maintenance == Maintenance.ABORTING ? State.ABORTING
                : entry.retired ? State.ERROR : entry.borrowed ? State.BUSY
                : entry.pinnedEditorId != null ? State.TRANSACTION : State.IDLE;
        return terminal(entry, state, entry.message);
    }

    private Snapshot terminal(Entry entry, State state, String message) {
        com.dbstudio.spi.ConnectionProfile profile = context.profile();
        long disconnectedAt = state == State.DISCONNECTED || state == State.ERROR ? System.currentTimeMillis() : 0L;
        String databaseName = "oracle".equals(profile.providerId()) ? profile.setting("service")
                : profile.setting("database");
        return new Snapshot(entry.slotId, entry.connectionId, entry.stateVersion, profile.id().toString(), profile.name(),
                profile.providerId(), databaseName, profile.setting("schema"), state, entry.editorId,
                entry.createdAt, entry.lastUsed,
                entry.lastProbeLatencyMs, disconnectedAt == 0L ? null : disconnectedAt, message);
    }

    private void changed(Entry entry) {
        entry.stateVersion++;
        limiter.connectionChanged(entry.permitKey, entry.connectionId);
        listener.changed();
    }

    private static ThreadFactory daemonFactory(final String prefix) {
        return new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
                thread.setDaemon(true); return thread;
            }
        };
    }

    private static String sanitize(String message) {
        return message == null || message.trim().isEmpty() ? "未知错误"
                : SqlLogSupport.sanitizeMessage(message);
    }
}
