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
    private final List<Entry> entries = new ArrayList<Entry>();
    private long generation;
    private boolean closed;

    WorkspaceJdbcPool(String key, DatabaseContext context, EditorConnectionLimiter limiter) {
        this.key = key; this.context = context; this.limiter = limiter;
    }

    DatabaseContext context() { return context; }

    synchronized Lease borrow() throws SQLException {
        ensureOpen();
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.closed) { iterator.remove(); continue; }
            if (entry.generation != generation || entry.retired || entry.borrowed || entry.pinnedEditorId != null) continue;
            if (!valid(entry.session)) { closeEntry(entry); iterator.remove(); continue; }
            entry.borrowed = true; entry.lastUsed = System.currentTimeMillis(); limiter.touch(entry.permitKey);
            LOG.debug("借用JDBC连接 pool={} generation={} physicalCount={}", key, generation, entries.size());
            return new Lease(this, entry);
        }

        final Entry created = new Entry(generation, key + ":" + UUID.randomUUID().toString());
        if (!limiter.acquire(created.permitKey, created, new Runnable() {
            @Override public void run() { /* Entry 控制对象已负责关闭对应 JDBC 会话。 */ }
        })) throw new ApiException("CONNECTION_LIMIT_REACHED", "已达到最大活动链接数，请处理事务或等待空闲链接回收");
        try {
            created.session = context.openEditorSession();
            created.borrowed = true;
            created.lastUsed = System.currentTimeMillis();
            entries.add(created);
            LOG.info("创建JDBC连接 pool={} generation={} physicalCount={}", key, generation, entries.size());
            return new Lease(this, created);
        } catch (SQLException exception) {
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
                LOG.debug("借用事务固定JDBC连接 pool={} editorId={}", key, editorId);
                return new Lease(this, entry);
            }
        }
        return null;
    }

    synchronized void pin(String editorId, Lease lease) {
        Entry entry = owned(lease);
        entry.pinnedEditorId = editorId;
        entry.borrowed = false;
        entry.lastUsed = System.currentTimeMillis();
        LOG.info("固定JDBC连接到事务编辑器 pool={} editorId={}", key, editorId);
    }

    synchronized void release(Lease lease) {
        Entry entry = owned(lease);
        if (entry.pinnedEditorId != null) {
            entry.borrowed = false; entry.lastUsed = System.currentTimeMillis();
            LOG.debug("释放事务固定JDBC借用 pool={} editorId={}", key, entry.pinnedEditorId);
            return;
        }
        if (!reset(entry.session)) { closeAndRemove(entry); return; }
        entry.borrowed = false; entry.lastUsed = System.currentTimeMillis();
        LOG.debug("归还JDBC连接 pool={} physicalCount={}", key, entries.size());
    }

    synchronized void unpinAndRelease(String editorId) {
        Entry entry = findPinned(editorId);
        if (entry == null) return;
        entry.pinnedEditorId = null;
        if (!reset(entry.session)) { closeAndRemove(entry); return; }
        entry.borrowed = false; entry.lastUsed = System.currentTimeMillis();
        LOG.info("解除事务固定并归还JDBC连接 pool={} editorId={}", key, editorId);
    }

    synchronized void closePinned(String editorId) {
        Entry entry = findPinned(editorId);
        if (entry != null) closeAndRemove(entry);
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
            if (entry.closed || entry.pinnedEditorId != null) continue;
            entry.retired = true; entry.retiredAt = now;
        }
    }

    synchronized void reap(long cutoffMillis) {
        Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.closed) { iterator.remove(); continue; }
            if (entry.borrowed || entry.pinnedEditorId != null) continue;
            long since = entry.retired ? entry.retiredAt : entry.lastUsed;
            if (since <= cutoffMillis) {
                LOG.info("回收空闲JDBC连接 pool={} generation={} retired={}", key, entry.generation, entry.retired);
                closeEntry(entry); iterator.remove();
            }
        }
        if (entries.isEmpty()) context.suspendMetadata();
    }

    synchronized int physicalCount() {
        int count = 0;
        for (Entry entry : entries) if (!entry.closed) count++;
        return count;
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

    private void closeAndRemove(Entry entry) { closeEntry(entry); entries.remove(entry); }

    private void closeEntry(Entry entry) {
        if (entry.closed) return;
        entry.closed = true; entry.borrowed = false;
        DatabaseSession session = entry.session; entry.session = null;
        if (session != null) try { session.close(); }
        catch (SQLException exception) { LOG.warn("关闭JDBC连接失败 pool={} generation={}", key, entry.generation, exception); }
        limiter.release(entry.permitKey);
    }

    private boolean reset(DatabaseSession session) {
        try {
            context.provider().connections().resetSession(session, context.profile());
            return true;
        } catch (SQLException exception) {
            LOG.warn("重置JDBC会话失败 pool={} message={}", key, SqlLogSupport.sanitizeMessage(exception.getMessage()));
            return false;
        }
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
        for (Entry entry : new ArrayList<Entry>(entries)) closeEntry(entry);
        entries.clear(); context.close();
        LOG.info("关闭JDBC连接池 pool={}", key);
    }

    static final class Lease {
        private final WorkspaceJdbcPool pool;
        private final Entry entry;
        private Lease(WorkspaceJdbcPool pool, Entry entry) { this.pool = pool; this.entry = entry; }
        DatabaseSession session() { return entry.session; }
        WorkspaceJdbcPool pool() { return pool; }
    }

    private final class Entry implements EditorConnectionLimiter.SessionControl {
        private final long generation;
        private final String permitKey;
        private DatabaseSession session;
        private boolean borrowed, retired, closed;
        private String pinnedEditorId;
        private long lastUsed = System.currentTimeMillis(), retiredAt;
        private Entry(long generation, String permitKey) { this.generation=generation; this.permitKey=permitKey; }
        @Override public boolean canSuspend() { return !borrowed && pinnedEditorId == null && !closed; }
        @Override public boolean suspend() {
            synchronized (WorkspaceJdbcPool.this) {
                if (!canSuspend()) return false;
                closeEntry(this); entries.remove(this); return true;
            }
        }
        @Override public long lastTouched() { return retired ? retiredAt : lastUsed; }
    }
}
