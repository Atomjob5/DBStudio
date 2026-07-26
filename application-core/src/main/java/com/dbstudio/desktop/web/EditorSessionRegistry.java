package com.dbstudio.desktop.web;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.query.QueryExecution;
import com.dbstudio.desktop.query.QueryResultListener;
import com.dbstudio.desktop.query.QueryRunner;
import com.dbstudio.desktop.query.ResultColumnResolver;
import com.dbstudio.desktop.query.StatementResult;
import com.dbstudio.spi.DatabaseSession;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 编辑器会话注册表。
 *
 * <p>编辑标签是逻辑会话，可在空闲回收后保留连接绑定并重新激活物理 QueryRunner/JDBC；同一标签
 * 同时只允许一个执行任务。该类由 HTTP 工作线程调用，实际 SQL 在每个标签自己的执行器中运行。</p>
 */
public final class EditorSessionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<UUID, EditorSession> sessions =
            new ConcurrentHashMap<UUID, EditorSession>();
    private final AtomicInteger sequence = new AtomicInteger(1);
    private volatile int maxRows;
    private volatile int streamBatchRows;

    public EditorSessionRegistry(int maxRows, int streamBatchRows) {
        this.maxRows = Math.max(1, maxRows);
        this.streamBatchRows = Math.max(1, streamBatchRows);
    }

    public EditorSession create() {
        return create(UUID.randomUUID());
    }

    /** 为浏览器 Workspace 恢复幂等创建逻辑编辑器，重复提交同一 UUID 返回已有标签。 */
    public EditorSession create(UUID id) {
        if (id == null) throw new RpcException("INVALID_EDITOR_ID", "查询标签 ID 无效");
        EditorSession created = new EditorSession(id, "查询 " + sequence.getAndIncrement());
        EditorSession existing = sessions.putIfAbsent(id, created);
        return existing == null ? created : existing;
    }

    /** 兼容旧核心契约测试的立即连接创建入口。 */
    public EditorSession create(DatabaseContext context) throws SQLException {
        EditorSession session = create();
        session.bind(context, context.profile().id().toString());
        activate(session);
        return session;
    }

    public void bind(EditorSession session, DatabaseContext context, String bindingKey) {
        session.bind(context, bindingKey);
    }

    public void activate(EditorSession session) throws SQLException {
        session.activate(maxRows, streamBatchRows);
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
        QueryRunner runner = session.runner();
        if (runner.isRunning() || session.activeExecutionId() != null) {
            throw new RpcException("QUERY_BUSY", "当前标签已有查询正在执行");
        }
        final UUID executionId = UUID.randomUUID();
        session.lastSql = statements.isEmpty() ? null : statements.get(statements.size() - 1).text();
        session.touch();
        runner.execute(statements, stopOnError, resultListener, () -> {
            if (!session.beginExecution(executionId)) {
                throw new RpcException("TRANSACTION_BUSY", "当前标签正在提交或回滚事务");
            }
            try {
                startedCallback.accept(executionId);
            } catch (RuntimeException exception) {
                session.endExecution(executionId);
                throw exception;
            }
        }).whenComplete((execution, failure) -> {
            session.endExecution(executionId);
            session.lastExecutionId = executionId;
            if (execution != null) session.lastExecution = execution;
            session.touch();
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
        for (EditorSession session : sessions.values()) session.setMaxRows(this.maxRows);
    }

    public void setStreamBatchRows(int streamBatchRows) {
        this.streamBatchRows = Math.max(1, streamBatchRows);
        for (EditorSession session : sessions.values()) session.setStreamBatchRows(this.streamBatchRows);
    }

    public int maxRows() { return maxRows; }
    public int streamBatchRows() { return streamBatchRows; }

    @Override public void close() {
        List<EditorSession> copy = new ArrayList<EditorSession>(sessions.values());
        sessions.clear();
        for (EditorSession session : copy) session.close();
    }

    public static final class EditorSession implements AutoCloseable {
        private final UUID id;
        private final String title;
        private volatile DatabaseContext context;
        private volatile String bindingKey;
        private volatile QueryRunner runner;
        private volatile long lastTouched = System.currentTimeMillis();
        private volatile UUID activeExecutionId;
        private volatile UUID lastExecutionId;
        private volatile QueryExecution lastExecution;
        private volatile String lastSql;
        private final AtomicBoolean transactionOperation = new AtomicBoolean();

        private EditorSession(UUID id, String title) { this.id = id; this.title = title; }
        public UUID id() { return id; }
        public String title() { return title; }
        public synchronized QueryRunner runner() {
            if (runner == null) throw new RpcException("EDITOR_CONNECTION_SUSPENDED", "编辑标签的数据库会话尚未激活");
            return runner;
        }
        public DatabaseContext context() {
            DatabaseContext current = context;
            if (current == null) throw new RpcException("NOT_CONNECTED", "当前编辑标签尚未选择数据库链接");
            return current;
        }
        public String bindingKey() { return bindingKey; }
        public boolean bound() { return bindingKey != null; }
        public boolean hasContext() { return context != null; }
        public boolean active() { return runner != null; }
        public UUID activeExecutionId() { return activeExecutionId; }
        public UUID lastExecutionId() { return lastExecutionId; }
        public QueryExecution lastExecution() { return lastExecution; }
        public String lastSql() { return lastSql; }
        public long lastTouched() { return lastTouched; }
        public boolean transactionOperationActive() { return transactionOperation.get(); }
        public synchronized boolean beginTransactionOperation() {
            if (activeExecutionId != null || transactionOperation.get()) return false;
            transactionOperation.set(true);
            return true;
        }
        public void endTransactionOperation() { transactionOperation.set(false); }
        public synchronized boolean beginExecution(UUID executionId) {
            if (activeExecutionId != null || transactionOperation.get()) return false;
            activeExecutionId = executionId;
            return true;
        }
        public synchronized void endExecution(UUID executionId) {
            if (executionId.equals(activeExecutionId)) activeExecutionId = null;
        }
        public void touch() { lastTouched = System.currentTimeMillis(); }

        /** 挂载由 Workspace JDBC 租约支持的执行器。 */
        public synchronized void attachRunner(QueryRunner value) {
            if (runner != null && runner != value) throw new RpcException("QUERY_BUSY", "编辑标签已有数据库任务");
            runner = value; touch();
        }

        /** 拆下租约执行器但不关闭底层 JDBC，会话归还由 Workspace 池负责。 */
        public synchronized QueryRunner detachRunner() {
            QueryRunner current = runner;
            runner = null; touch(); return current;
        }

        public synchronized void bind(DatabaseContext value, String key) {
            if (activeExecutionId != null) throw new RpcException("QUERY_BUSY", "查询执行期间不能切换数据库链接");
            if (transactionDirty()) throw new RpcException("TRANSACTION_DECISION_REQUIRED", "切换链接前必须提交或回滚事务");
            closeRunner();
            context = value;
            bindingKey = key;
            lastExecution = null;
            lastExecutionId = null;
            lastSql = null;
            touch();
        }

        public synchronized void bindLogical(String key) {
            if (activeExecutionId != null) throw new RpcException("QUERY_BUSY", "查询执行期间不能切换数据库链接");
            if (transactionDirty()) throw new RpcException("TRANSACTION_DECISION_REQUIRED", "切换链接前必须提交或回滚事务");
            closeRunner(); context = null; bindingKey = key; touch();
        }

        public synchronized void activate(int maxRows, int streamBatchRows) throws SQLException {
            if (runner != null) { touch(); return; }
            DatabaseContext current = context;
            if (current == null) throw new RpcException("NOT_CONNECTED", "当前编辑标签尚未选择数据库链接");
            DatabaseSession opened = current.openEditorSession();
            try {
                ResultColumnResolver resolver = current.resultColumnResolver();
                runner = new QueryRunner(opened, maxRows, streamBatchRows, resolver,
                        current.provider().dialect(), true);
                opened = null;
            } finally {
                if (opened != null) opened.close();
            }
            touch();
        }

        public synchronized boolean canSuspend() {
            return runner != null && activeExecutionId == null && !runner.isRunning() && !runner.isTransactionDirty();
        }

        public synchronized boolean suspend() {
            if (!canSuspend()) return false;
            closeRunner();
            return true;
        }

        public synchronized void unbind() {
            if (activeExecutionId != null) throw new RpcException("QUERY_BUSY", "查询执行期间不能解绑数据库链接");
            if (transactionDirty()) throw new RpcException("TRANSACTION_DECISION_REQUIRED", "解绑前必须提交或回滚事务");
            closeRunner();
            context = null;
            bindingKey = null;
            lastExecution = null;
            lastExecutionId = null;
            lastSql = null;
            touch();
        }

        public boolean transactionDirty() {
            QueryRunner current = runner;
            return current != null && current.isTransactionDirty();
        }
        public CompletableFuture<Void> commit() {
            QueryRunner current = runner;
            if (current == null) return CompletableFuture.completedFuture(null);
            touch(); return current.commit();
        }
        public CompletableFuture<Void> rollback() {
            QueryRunner current = runner;
            if (current == null) return CompletableFuture.completedFuture(null);
            touch(); return current.rollback();
        }
        public boolean cancel() { QueryRunner current = runner; return current != null && current.cancel(); }
        public void setMaxRows(int maxRows) { QueryRunner current = runner; if (current != null) current.setMaxRows(maxRows); }
        public void setStreamBatchRows(int rows) { QueryRunner current = runner; if (current != null) current.setStreamBatchRows(rows); }

        public synchronized void appendResultRows(int resultIndex, List<List<String>> rows, boolean hasMore) {
            QueryExecution execution = lastExecution;
            if (execution == null || resultIndex < 0 || resultIndex >= execution.results().size()) return;
            List<StatementResult> results = new ArrayList<StatementResult>(execution.results());
            StatementResult source = results.get(resultIndex);
            List<List<String>> combined = new ArrayList<List<String>>(source.rows());
            combined.addAll(rows);
            results.set(resultIndex, new StatementResult(source.sql(), source.type(), source.columns(), source.columnDetails(),
                    source.mutationTarget(), combined, source.updateCount(), hasMore, source.duration(), source.errorMessage()));
            lastExecution = new QueryExecution(results, execution.duration(), execution.cancelled());
            touch();
        }

        private void closeRunner() {
            QueryRunner current = runner;
            runner = null;
            if (current != null) current.close();
        }

        @Override public synchronized void close() {
            closeRunner(); context = null; bindingKey = null;
        }
    }

    public interface ExecutionCallback {
        void completed(UUID executionId, QueryExecution execution, Throwable failure);
    }
}
