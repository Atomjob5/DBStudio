package com.dbstudio.desktop.web;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.query.QueryExecution;
import com.dbstudio.desktop.query.ChunkedList;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.MDC;

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
    private volatile int clobMaxCharacters;

    public EditorSessionRegistry(int maxRows, int streamBatchRows) {
        this(maxRows, streamBatchRows, QueryRunner.DEFAULT_CLOB_MAX_CHARACTERS);
    }

    public EditorSessionRegistry(int maxRows, int streamBatchRows, int clobMaxCharacters) {
        this.maxRows = Math.max(1, maxRows);
        this.streamBatchRows = Math.max(1, streamBatchRows);
        this.clobMaxCharacters = Math.max(QueryRunner.MIN_CLOB_MAX_CHARACTERS,
                Math.min(QueryRunner.MAX_CLOB_MAX_CHARACTERS, clobMaxCharacters));
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
        session.activate(maxRows, streamBatchRows, clobMaxCharacters);
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
        return execute(session, statements, stopOnError, false, startedCallback, QueryResultListener.NONE, callback);
    }

    public UUID execute(final EditorSession session, final List<SqlStatement> statements,
                        boolean stopOnError, boolean retainPreviousResults, Consumer<UUID> startedCallback,
                        QueryResultListener resultListener, final ExecutionCallback callback) {
        return execute(session, statements, stopOnError, retainPreviousResults, startedCallback, resultListener, callback, null);
    }

    public UUID execute(final EditorSession session, final List<SqlStatement> statements,
                        boolean stopOnError, boolean retainPreviousResults, Consumer<UUID> startedCallback,
                        QueryResultListener resultListener, final ExecutionCallback callback,
                        com.dbstudio.spi.ExecutionPlanAdapter planAdapter) {
        QueryRunner runner = session.runner();
        if (runner.isRunning() || session.activeExecutionId() != null) {
            throw new RpcException("QUERY_BUSY", "当前标签已有查询正在执行");
        }
        final UUID executionId = UUID.randomUUID();
        session.lastSql = statements.isEmpty() ? null : statements.get(statements.size() - 1).text();
        session.touch();
        String previousExecutionId = MDC.get("executionId");
        MDC.put("executionId", executionId.toString());
        try {
            runner.execute(statements, stopOnError, resultListener, () -> {
                if (!session.beginExecution(executionId, retainPreviousResults)) {
                    throw new RpcException("TRANSACTION_BUSY", "当前标签正在提交或回滚事务");
                }
                try {
                    startedCallback.accept(executionId);
                } catch (RuntimeException exception) {
                    session.endExecution(executionId);
                    throw exception;
                }
            }, planAdapter).whenComplete((execution, failure) -> {
                session.completeExecution(executionId, execution);
                callback.completed(executionId, execution, failure);
            });
        } finally {
            if (previousExecutionId == null) MDC.remove("executionId");
            else MDC.put("executionId", previousExecutionId);
        }
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

    public void setClobMaxCharacters(int clobMaxCharacters) {
        this.clobMaxCharacters = Math.max(QueryRunner.MIN_CLOB_MAX_CHARACTERS,
                Math.min(QueryRunner.MAX_CLOB_MAX_CHARACTERS, clobMaxCharacters));
        for (EditorSession session : sessions.values()) session.setClobMaxCharacters(this.clobMaxCharacters);
    }

    public int maxRows() { return maxRows; }
    public int streamBatchRows() { return streamBatchRows; }
    public int clobMaxCharacters() { return clobMaxCharacters; }

    @Override public void close() {
        List<EditorSession> copy = new ArrayList<EditorSession>(sessions.values());
        sessions.clear();
        for (EditorSession session : copy) session.close();
    }

    public static final class EditorSession implements AutoCloseable {
        private final UUID id;
        private volatile String title;
        private volatile DatabaseContext context;
        private volatile String bindingKey;
        private volatile QueryRunner runner;
        private volatile long lastTouched = System.currentTimeMillis();
        private volatile UUID activeExecutionId;
        private volatile UUID lastExecutionId;
        private volatile QueryExecution lastExecution;
        private volatile String lastSql;
        private final AtomicBoolean transactionOperation = new AtomicBoolean();
        /** 已完成结果按 executionId 保存，临时 SELECT 不会替换此前的结果。 */
        private final Map<UUID, QueryExecution> retainedExecutions = new LinkedHashMap<UUID, QueryExecution>();
        private final Map<UUID, Map<String, String>> originalResultValues =
                new HashMap<UUID, Map<String, String>>();
        private final Map<UUID, Map<Integer, StatementResult>> originalResultSnapshots =
                new HashMap<UUID, Map<Integer, StatementResult>>();
        /** Results retained after an aborted JDBC connection are snapshots only and cannot be exported server-side. */
        private final Set<UUID> historicalExecutions = new HashSet<UUID>();

        private EditorSession(UUID id, String title) { this.id = id; this.title = title; }
        public UUID id() { return id; }
        public String title() { return title; }
        public synchronized void rename(String title) {
            this.title = title;
            touch();
        }
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
        public synchronized QueryExecution execution(UUID executionId) {
            return executionId == null ? null : retainedExecutions.get(executionId);
        }
        public synchronized Map<UUID, QueryExecution> retainedExecutions() {
            return Collections.unmodifiableMap(new LinkedHashMap<UUID, QueryExecution>(retainedExecutions));
        }
        public synchronized boolean hasExecution(UUID executionId) {
            return executionId != null && retainedExecutions.containsKey(executionId);
        }
        public synchronized boolean historicalExecution(UUID executionId) {
            return executionId != null && historicalExecutions.contains(executionId);
        }
        public String lastSql() { return lastSql; }
        public long lastTouched() { return lastTouched; }
        public boolean transactionOperationActive() { return transactionOperation.get(); }
        public synchronized boolean resultChangesDirty() {
            return !originalResultValues.isEmpty() || !originalResultSnapshots.isEmpty();
        }
        public synchronized boolean beginTransactionOperation() {
            if (activeExecutionId != null || transactionOperation.get()) return false;
            transactionOperation.set(true);
            return true;
        }
        public void endTransactionOperation() { transactionOperation.set(false); }
        public synchronized boolean beginExecution(UUID executionId) {
            return beginExecution(executionId, true);
        }
        public synchronized boolean beginExecution(UUID executionId, boolean retainPreviousResults) {
            if (activeExecutionId != null || transactionOperation.get()) return false;
            if (!retainPreviousResults) clearRetainedResults();
            historicalExecutions.remove(executionId);
            activeExecutionId = executionId;
            return true;
        }
        public synchronized void endExecution(UUID executionId) {
            if (executionId.equals(activeExecutionId)) activeExecutionId = null;
        }
        private synchronized boolean completeExecution(UUID executionId, QueryExecution execution) {
            if (!executionId.equals(activeExecutionId)) return false;
            activeExecutionId = null;
            lastExecutionId = executionId;
            if (execution != null) {
                lastExecution = execution;
                retainedExecutions.put(executionId, execution);
            }
            touch(); return true;
        }
        public synchronized void forceRetireDatabaseWork(UUID executionId) {
            if (executionId == null || executionId.equals(activeExecutionId)) activeExecutionId = null;
            historicalExecutions.addAll(retainedExecutions.keySet());
            if (executionId != null && retainedExecutions.containsKey(executionId)) {
                historicalExecutions.add(executionId);
            }
            transactionOperation.set(false);
            clearResultChanges();
            touch();
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
            clearRetainedResults();
            touch();
        }

        public synchronized void bindLogical(String key) {
            if (activeExecutionId != null) throw new RpcException("QUERY_BUSY", "查询执行期间不能切换数据库链接");
            if (transactionDirty()) throw new RpcException("TRANSACTION_DECISION_REQUIRED", "切换链接前必须提交或回滚事务");
            closeRunner(); context = null; bindingKey = key; touch();
        }

        public synchronized void activate(int maxRows, int streamBatchRows) throws SQLException {
            activate(maxRows, streamBatchRows, QueryRunner.DEFAULT_CLOB_MAX_CHARACTERS);
        }

        public synchronized void activate(int maxRows, int streamBatchRows, int clobMaxCharacters) throws SQLException {
            if (runner != null) { touch(); return; }
            DatabaseContext current = context;
            if (current == null) throw new RpcException("NOT_CONNECTED", "当前编辑标签尚未选择数据库链接");
            DatabaseSession opened = current.openEditorSession();
            try {
                ResultColumnResolver resolver = current.resultColumnResolver();
                runner = new QueryRunner(opened, maxRows, streamBatchRows, clobMaxCharacters, resolver,
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
            clearRetainedResults();
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
        public void setClobMaxCharacters(int characters) {
            QueryRunner current = runner;
            if (current != null) current.setClobMaxCharacters(characters);
        }

        public synchronized void appendResultRows(UUID executionId, int resultIndex, List<List<String>> rows,
                                                  List<String> rowIds, List<List<String>> rowLocators,
                                                  boolean hasMore) {
            QueryExecution execution = retainedExecutions.get(executionId);
            if (execution == null || resultIndex < 0 || resultIndex >= execution.results().size()) return;
            List<StatementResult> results = new ArrayList<StatementResult>(execution.results());
            StatementResult source = results.get(resultIndex);
            List<List<String>> combined = ChunkedList.append(source.rows(), rows);
            List<String> combinedIds = ChunkedList.append(source.rowIds(), rowIds);
            List<List<String>> combinedLocators = ChunkedList.append(source.rowLocators(), rowLocators);
            results.set(resultIndex, new StatementResult(source.sql(), source.type(), source.columns(), source.columnDetails(),
                    source.mutationTarget(), combined, combinedIds, combinedLocators, source.updateCount(), hasMore,
                    source.duration(), source.errorMessage()));
            replaceExecution(executionId, new QueryExecution(results, execution.duration(), execution.cancelled()));
            touch();
        }

        /** Updates the terminal truncation flag without appending another batch. */
        public synchronized void completeResultRows(UUID executionId, int resultIndex, boolean hasMore) {
            QueryExecution execution = retainedExecutions.get(executionId);
            if (execution == null || resultIndex < 0 || resultIndex >= execution.results().size()) return;
            List<StatementResult> results = new ArrayList<StatementResult>(execution.results());
            StatementResult source = results.get(resultIndex);
            if (source.truncated() == hasMore) return;
            results.set(resultIndex, new StatementResult(source.sql(), source.type(), source.columns(), source.columnDetails(),
                    source.mutationTarget(), source.rows(), source.rowIds(), source.rowLocators(), source.updateCount(),
                    hasMore, source.duration(), source.errorMessage()));
            replaceExecution(executionId, new QueryExecution(results, execution.duration(), execution.cancelled()));
            touch();
        }

        public synchronized void recordResultChanges(UUID executionId, int resultIndex,
                                                     List<QueryRunner.RowChange> changes) {
            QueryExecution execution = retainedExecutions.get(executionId);
            if (execution == null || resultIndex < 0 || resultIndex >= execution.results().size()) {
                throw new RpcException("STALE_RESULT", "查询结果已经过期，请重新执行");
            }
            List<StatementResult> results = new ArrayList<StatementResult>(execution.results());
            StatementResult source = results.get(resultIndex);
            Map<String, String> originals = originalResultValues.computeIfAbsent(executionId,
                    ignored -> new HashMap<String, String>());
            List<List<String>> rows = mutableRows(source.rows());
            for (QueryRunner.RowChange rowChange : changes) {
                if (rowChange.rowIndex() < 0 || rowChange.rowIndex() >= rows.size()) {
                    throw new RpcException("STALE_RESULT", "查询结果行已经过期，请重新执行");
                }
                List<String> row = rows.get(rowChange.rowIndex());
                for (QueryRunner.CellChange cell : rowChange.cells()) {
                    if (cell.columnIndex() < 0 || cell.columnIndex() >= row.size()) {
                        throw new RpcException("STALE_RESULT", "查询结果字段已经过期，请重新执行");
                    }
                    String key = resultCellKey(resultIndex, rowChange.rowIndex(), cell.columnIndex());
                    if (!originals.containsKey(key)) {
                        originals.put(key, row.get(cell.columnIndex()));
                    }
                    row.set(cell.columnIndex(), cell.value());
                }
            }
            results.set(resultIndex, resultWithRows(source, rows));
            replaceExecution(executionId, new QueryExecution(results, execution.duration(), execution.cancelled()));
            touch();
        }

        public synchronized void commitResultChanges() {
            retireResultChanges();
        }

        public synchronized void retireResultChanges() {
            clearResultChanges();
            touch();
        }

        public synchronized void rollbackResultChanges() {
            if (originalResultValues.isEmpty() && originalResultSnapshots.isEmpty()) return;
            for (UUID executionId : new ArrayList<UUID>(retainedExecutions.keySet())) {
                QueryExecution execution = retainedExecutions.get(executionId);
                if (execution == null) continue;
                List<StatementResult> results = new ArrayList<StatementResult>(execution.results());
                Map<Integer, StatementResult> snapshots = originalResultSnapshots.get(executionId);
                if (snapshots != null) for (Map.Entry<Integer, StatementResult> snapshot : snapshots.entrySet()) {
                    if (snapshot.getKey() >= 0 && snapshot.getKey() < results.size()) {
                        results.set(snapshot.getKey(), snapshot.getValue());
                    }
                }
                Map<Integer, List<List<String>>> rowsByResult = new HashMap<Integer, List<List<String>>>();
                Map<String, String> originals = originalResultValues.get(executionId);
                if (originals != null) for (Map.Entry<String, String> entry : originals.entrySet()) {
                    String[] parts = entry.getKey().split(":", 3);
                    int resultIndex = Integer.parseInt(parts[0]);
                    int rowIndex = Integer.parseInt(parts[1]);
                    int columnIndex = Integer.parseInt(parts[2]);
                    if (resultIndex < 0 || resultIndex >= results.size()) continue;
                    List<List<String>> rows = rowsByResult.get(resultIndex);
                    if (rows == null) {
                        rows = mutableRows(results.get(resultIndex).rows());
                        rowsByResult.put(resultIndex, rows);
                    }
                    if (rowIndex >= 0 && rowIndex < rows.size()
                            && columnIndex >= 0 && columnIndex < rows.get(rowIndex).size()) {
                        rows.get(rowIndex).set(columnIndex, entry.getValue());
                    }
                }
                for (Map.Entry<Integer, List<List<String>>> entry : rowsByResult.entrySet()) {
                    StatementResult source = results.get(entry.getKey());
                    results.set(entry.getKey(), resultWithRows(source, entry.getValue()));
                }
                replaceExecution(executionId, new QueryExecution(results, execution.duration(), execution.cancelled()));
            }
            clearResultChanges();
            touch();
        }

        public synchronized List<ResultPatch> recordResultOperations(UUID executionId, int resultIndex,
                                                                       QueryRunner.MutationBatchResult batch) {
            QueryExecution execution = retainedExecutions.get(executionId);
            if (execution == null || resultIndex < 0 || resultIndex >= execution.results().size()) {
                throw new RpcException("STALE_RESULT", "查询结果已经过期，请重新执行");
            }
            List<StatementResult> results = new ArrayList<StatementResult>(execution.results());
            StatementResult source = results.get(resultIndex);
            Map<Integer, StatementResult> snapshots = originalResultSnapshots.computeIfAbsent(executionId,
                    ignored -> new HashMap<Integer, StatementResult>());
            if (!snapshots.containsKey(resultIndex)) snapshots.put(resultIndex, source);
            List<List<String>> rows = mutableRows(source.rows());
            List<String> rowIds = new ArrayList<String>(source.rowIds());
            List<List<String>> rowLocators = new ArrayList<List<String>>(source.rowLocators());
            List<Integer> deleted = new ArrayList<Integer>();
            List<ResultPatch> patches = new ArrayList<ResultPatch>();
            for (QueryRunner.OperationResult operation : batch.operations()) {
                if (operation.kind() == QueryRunner.MutationKind.UPDATE) {
                    if (operation.rowIndex() < 0 || operation.rowIndex() >= rows.size()) {
                        throw new RpcException("STALE_RESULT", "查询结果行已经过期，请重新执行");
                    }
                    if (operation.visible()) {
                        rows.set(operation.rowIndex(), new ArrayList<String>(operation.row()));
                        rowLocators.set(operation.rowIndex(), new ArrayList<String>(operation.locator()));
                        patches.add(new ResultPatch(operation.operationId(), "update", operation.rowIndex(),
                                rowIds.get(operation.rowIndex()), operation.row()));
                    } else {
                        deleted.add(operation.rowIndex());
                        patches.add(new ResultPatch(operation.operationId(), "delete", operation.rowIndex(),
                                rowIds.get(operation.rowIndex()), Collections.<String>emptyList()));
                    }
                } else if (operation.kind() == QueryRunner.MutationKind.DELETE) {
                    deleted.add(operation.rowIndex());
                    patches.add(new ResultPatch(operation.operationId(), "delete", operation.rowIndex(),
                            rowIds.get(operation.rowIndex()), operation.row()));
                } else if (operation.kind() == QueryRunner.MutationKind.INSERT) {
                    if (operation.visible()) {
                        String rowId = UUID.randomUUID().toString();
                        rows.add(new ArrayList<String>(operation.row()));
                        rowIds.add(rowId);
                        rowLocators.add(new ArrayList<String>(operation.locator()));
                        patches.add(new ResultPatch(operation.operationId(), "insert", rows.size() - 1,
                                rowId, operation.row()));
                    } else {
                        patches.add(new ResultPatch(operation.operationId(), "delete", -1, "",
                                Collections.<String>emptyList()));
                    }
                }
            }
            Collections.sort(deleted, Collections.reverseOrder());
            for (Integer rowIndex : deleted) {
                if (rowIndex >= 0 && rowIndex < rows.size()) {
                    rows.remove((int) rowIndex); rowIds.remove((int) rowIndex); rowLocators.remove((int) rowIndex);
                }
            }
            results.set(resultIndex, new StatementResult(source.sql(), source.type(), source.columns(),
                    source.columnDetails(), source.mutationTarget(), rows, rowIds, rowLocators,
                    source.updateCount(), source.truncated(), source.duration(), source.errorMessage()));
            replaceExecution(executionId, new QueryExecution(results, execution.duration(), execution.cancelled()));
            touch();
            return Collections.unmodifiableList(patches);
        }

        public synchronized boolean removeExecution(UUID executionId) {
            if (executionId == null || executionId.equals(activeExecutionId)
                    || retainedExecutions.remove(executionId) == null) return false;
            historicalExecutions.remove(executionId);
            originalResultValues.remove(executionId);
            originalResultSnapshots.remove(executionId);
            if (executionId.equals(lastExecutionId)) {
                lastExecutionId = null;
                lastExecution = null;
                for (Map.Entry<UUID, QueryExecution> item : retainedExecutions.entrySet()) {
                    lastExecutionId = item.getKey();
                    lastExecution = item.getValue();
                }
            }
            touch();
            return true;
        }

        private void replaceExecution(UUID executionId, QueryExecution execution) {
            retainedExecutions.put(executionId, execution);
            if (executionId.equals(lastExecutionId)) lastExecution = execution;
        }

        private void clearResultChanges() {
            originalResultValues.clear();
            originalResultSnapshots.clear();
        }

        private void clearRetainedResults() {
            retainedExecutions.clear();
            historicalExecutions.clear();
            clearResultChanges();
        }

        public static final class ResultPatch {
            private final String operationId;
            private final String kind;
            private final int rowIndex;
            private final String rowId;
            private final List<String> row;
            private ResultPatch(String operationId, String kind, int rowIndex, String rowId, List<String> row) {
                this.operationId = operationId; this.kind = kind; this.rowIndex = rowIndex; this.rowId = rowId;
                this.row = Collections.unmodifiableList(new ArrayList<String>(row));
            }
            public String operationId() { return operationId; }
            public String kind() { return kind; }
            public int rowIndex() { return rowIndex; }
            public String rowId() { return rowId; }
            public List<String> row() { return row; }
        }

        private static List<List<String>> mutableRows(List<List<String>> source) {
            List<List<String>> result = new ArrayList<List<String>>(source.size());
            for (List<String> row : source) result.add(new ArrayList<String>(row));
            return result;
        }

        private static StatementResult resultWithRows(StatementResult source, List<List<String>> rows) {
            return new StatementResult(source.sql(), source.type(), source.columns(), source.columnDetails(),
                    source.mutationTarget(), rows, source.rowIds(), source.rowLocators(), source.updateCount(), source.truncated(),
                    source.duration(), source.errorMessage());
        }

        private static String resultCellKey(int resultIndex, int rowIndex, int columnIndex) {
            return resultIndex + ":" + rowIndex + ":" + columnIndex;
        }

        private void closeRunner() {
            QueryRunner current = runner;
            runner = null;
            if (current != null) current.close();
        }

        @Override public synchronized void close() {
            closeRunner(); context = null; bindingKey = null; clearRetainedResults();
            lastExecution = null; lastExecutionId = null;
        }
    }

    public interface ExecutionCallback {
        void completed(UUID executionId, QueryExecution execution, Throwable failure);
    }
}
