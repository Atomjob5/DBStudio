package com.dbstudio.desktop.web;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.query.QueryExecution;
import com.dbstudio.desktop.query.QueryResultListener;
import com.dbstudio.desktop.query.QueryRunner;
import com.dbstudio.spi.SqlStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class EditorSessionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<UUID, EditorSession> sessions =
            new ConcurrentHashMap<UUID, EditorSession>();
    private final AtomicInteger sequence = new AtomicInteger(1);
    private volatile int maxRows;

    public EditorSessionRegistry(int maxRows) { this.maxRows = Math.max(1, maxRows); }

    public EditorSession create(DatabaseContext context) throws SQLException {
        UUID id = UUID.randomUUID();
        EditorSession session = new EditorSession(id, "查询 " + sequence.getAndIncrement(),
                new QueryRunner(context.openEditorSession(), maxRows));
        sessions.put(id, session);
        return session;
    }

    public EditorSession require(String id) {
        try {
            EditorSession session = sessions.get(UUID.fromString(id));
            if (session == null) throw new RpcException("EDITOR_NOT_FOUND", "查询标签不存在或已关闭");
            return session;
        } catch (IllegalArgumentException exception) {
            throw new RpcException("INVALID_EDITOR_ID", "查询标签 ID 无效", exception);
        }
    }

    public UUID execute(EditorSession session, List<SqlStatement> statements, boolean stopOnError,
                        Consumer<UUID> startedCallback, ExecutionCallback callback) {
        return execute(session, statements, stopOnError, startedCallback, QueryResultListener.NONE, callback);
    }

    public UUID execute(final EditorSession session, final List<SqlStatement> statements,
                        boolean stopOnError, Consumer<UUID> startedCallback,
                        QueryResultListener resultListener, final ExecutionCallback callback) {
        if (session.runner().isRunning() || session.activeExecutionId() != null) {
            throw new RpcException("QUERY_BUSY", "当前标签已有查询正在执行");
        }
        final UUID executionId = UUID.randomUUID();
        session.activeExecutionId = executionId;
        session.lastSql = statements.isEmpty() ? null : statements.get(statements.size() - 1).text();
        startedCallback.accept(executionId);
        session.runner().execute(statements, stopOnError, resultListener).whenComplete((execution, failure) -> {
            session.activeExecutionId = null;
            session.lastExecutionId = executionId;
            if (execution != null) session.lastExecution = execution;
            callback.completed(executionId, execution, failure);
        });
        return executionId;
    }

    public boolean close(String id) {
        EditorSession session = require(id);
        if (!sessions.remove(session.id(), session)) return false;
        session.close();
        return true;
    }

    public boolean hasDirtyTransactions() {
        for (EditorSession session : sessions.values()) if (session.transactionDirty()) return true;
        return false;
    }

    public Collection<EditorSession> all() {
        return Collections.unmodifiableList(new ArrayList<EditorSession>(sessions.values()));
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = Math.max(1, maxRows);
        for (EditorSession session : sessions.values()) session.runner().setMaxRows(this.maxRows);
    }

    @Override public void close() {
        List<EditorSession> copy = new ArrayList<EditorSession>(sessions.values());
        sessions.clear();
        for (EditorSession session : copy) session.close();
    }

    public static final class EditorSession implements AutoCloseable {
        private final UUID id;
        private final String title;
        private final QueryRunner runner;
        private volatile UUID activeExecutionId;
        private volatile UUID lastExecutionId;
        private volatile QueryExecution lastExecution;
        private volatile String lastSql;

        private EditorSession(UUID id, String title, QueryRunner runner) {
            this.id = id; this.title = title; this.runner = runner;
        }
        public UUID id() { return id; }
        public String title() { return title; }
        public QueryRunner runner() { return runner; }
        public UUID activeExecutionId() { return activeExecutionId; }
        public UUID lastExecutionId() { return lastExecutionId; }
        public QueryExecution lastExecution() { return lastExecution; }
        public String lastSql() { return lastSql; }
        public boolean transactionDirty() { return runner.isTransactionDirty(); }
        public CompletableFuture<Void> commit() { return runner.commit(); }
        public CompletableFuture<Void> rollback() { return runner.rollback(); }
        public boolean cancel() { return runner.cancel(); }
        @Override public void close() { runner.close(); }
    }

    public interface ExecutionCallback {
        void completed(UUID executionId, QueryExecution execution, Throwable failure);
    }
}
