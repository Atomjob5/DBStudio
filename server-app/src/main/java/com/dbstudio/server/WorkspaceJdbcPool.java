package com.dbstudio.server;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.spi.DatabaseSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** A small workspace-scoped JDBC queue with transaction pinning and disconnect generations. */
final class WorkspaceJdbcPool implements AutoCloseable {
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
            return new Lease(this, entry);
        }

        final Entry created = new Entry(generation, key + ":" + UUID.randomUUID().toString());
        if (!limiter.acquire(created.permitKey, created, new Runnable() {
            @Override public void run() { /* Entry control has already closed the JDBC session. */ }
        })) throw new ApiException("CONNECTION_LIMIT_REACHED", "已达到最大活动链接数，请处理事务或等待空闲链接回收");
        try {
            created.session = context.openEditorSession();
            created.borrowed = true;
            created.lastUsed = System.currentTimeMillis();
            entries.add(created);
            return new Lease(this, created);
        } catch (SQLException exception) {
            limiter.release(created.permitKey);
            throw exception;
        }
    }

    synchronized Lease pinned(String editorId) {
        for (Entry entry : entries) {
            if (!entry.closed && editorId.equals(entry.pinnedEditorId)) {
                entry.borrowed = true; entry.lastUsed = System.currentTimeMillis(); limiter.touch(entry.permitKey);
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
    }

    synchronized void release(Lease lease) {
        Entry entry = owned(lease);
        if (entry.pinnedEditorId != null) { entry.borrowed = false; entry.lastUsed = System.currentTimeMillis(); return; }
        if (!reset(entry.session)) { closeAndRemove(entry); return; }
        entry.borrowed = false; entry.lastUsed = System.currentTimeMillis();
    }

    synchronized void unpinAndRelease(String editorId) {
        Entry entry = findPinned(editorId);
        if (entry == null) return;
        entry.pinnedEditorId = null;
        if (!reset(entry.session)) { closeAndRemove(entry); return; }
        entry.borrowed = false; entry.lastUsed = System.currentTimeMillis();
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
            if (since <= cutoffMillis) { closeEntry(entry); iterator.remove(); }
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
        if (session != null) try { session.close(); } catch (SQLException ignored) { }
        limiter.release(entry.permitKey);
    }

    private boolean reset(DatabaseSession session) {
        try {
            context.provider().connections().resetSession(session, context.profile());
            return true;
        } catch (SQLException exception) { return false; }
    }

    private static boolean valid(DatabaseSession session) {
        if (session == null) return false;
        try { return !session.isClosed() && session.jdbcConnection().isValid(2); }
        catch (SQLException exception) { return false; }
    }

    private void ensureOpen() throws SQLException {
        if (closed) throw new SQLException("Workspace JDBC pool is closed");
    }

    @Override public synchronized void close() {
        closed = true;
        for (Entry entry : new ArrayList<Entry>(entries)) closeEntry(entry);
        entries.clear(); context.close();
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
