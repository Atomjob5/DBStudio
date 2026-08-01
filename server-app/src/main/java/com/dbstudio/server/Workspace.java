package com.dbstudio.server;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.query.QueryExecution;
import com.dbstudio.desktop.query.QueryResultListener;
import com.dbstudio.desktop.query.QueryRunner;
import com.dbstudio.desktop.logging.SqlLogSupport;
import com.dbstudio.desktop.query.QueryRunner.PageResult;
import com.dbstudio.desktop.query.ResultMutationTarget;
import com.dbstudio.desktop.web.EditorSessionRegistry;
import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import com.dbstudio.spi.SqlStatement;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个持久化 Workspace 的进程内运行时。
 *
 * <p>编辑器只保存逻辑连接绑定，非事务 SQL 从对应连接池借用 JDBC；事务或锁定查询完成后
 * 会把连接固定到编辑器，直到提交、回滚、切换连接或断连保护超时。</p>
 */
final class Workspace implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(Workspace.class);
    private final String id;
    private final EditorSessionRegistry editors;
    private final WorkspaceEventChannel events;
    private final Path temporaryDirectory;
    private final Map<String, Path> uploads = new ConcurrentHashMap<String, Path>();
    private final Map<String, LargeValueDraft> largeValueDrafts = new ConcurrentHashMap<String, LargeValueDraft>();
    private final ExecutorService tasks;
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final EditorConnectionLimiter limiter;
    private final Map<String, ContextReference> contexts = new ConcurrentHashMap<String, ContextReference>();
    private final Map<String, SavedProfile> bindings = new ConcurrentHashMap<String, SavedProfile>();
    private final Map<String, ActiveLease> activeLeases = new ConcurrentHashMap<String, ActiveLease>();
    private final Map<UUID, char[]> credentials = new ConcurrentHashMap<UUID, char[]>();
    private volatile boolean disconnected;
    private volatile boolean autoCommit;

    Workspace(String id, int maxRows, int streamBatchRows, boolean autoCommit,
              ObjectMapper mapper, Path temporaryDirectory,
              EditorConnectionLimiter limiter) {
        this.id = id;
        this.editors = new EditorSessionRegistry(maxRows, streamBatchRows);
        this.autoCommit = autoCommit;
        this.events = new WorkspaceEventChannel(mapper, id);
        this.temporaryDirectory = temporaryDirectory;
        this.limiter = limiter;
        this.tasks = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger sequence = new AtomicInteger();
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "dbstudio-task-" + sequence.incrementAndGet());
                thread.setDaemon(true); return thread;
            }
        });
        LOG.info("创建Workspace运行时 workspaceId={} maxRows={} streamBatchRows={} autoCommit={}",
                id, maxRows, streamBatchRows, autoCommit);
    }

    String id() { return id; }
    EditorSessionRegistry editors() { return editors; }
    WorkspaceEventChannel events() { return events; }
    int activeTasks() { return activeTasks.get(); }
    boolean disconnected() { return disconnected; }

    List<Map<String,Object>> editorRuntimeStates() {
        List<Map<String,Object>> result = new ArrayList<Map<String,Object>>();
        for (EditorSession editor : editors.all()) {
            boolean transaction = editor.transactionDirty();
            result.add(ApiPayloads.map("editorId", editor.id().toString(),
                    "busy", editor.activeExecutionId() != null,
                    "activeExecutionId", editor.activeExecutionId() == null
                            ? null : editor.activeExecutionId().toString(),
                    "transactionDirty", transaction,
                    "resultChangesDirty", editor.resultChangesDirty(),
                    "transactionState", transaction ? (disconnected ? "disconnected-protected" : "active") : "none",
                    "connectionState", !editor.bound() ? "unbound" : editor.hasContext() ? "ready" : "credentials-required"));
        }
        return result;
    }

    synchronized DatabaseContext context(String key) {
        ContextReference reference = contexts.get(key);
        return reference == null ? null : reference.context;
    }

    synchronized DatabaseContext registerContext(String key, DatabaseContext created) {
        ContextReference existing = contexts.get(key);
        if (existing != null) { created.close(); return existing.context; }
        ContextReference reference = new ContextReference(created,
                new WorkspaceJdbcPool(id + ":" + key, created, limiter, autoCommit));
        contexts.put(key, reference);
        LOG.info("注册Workspace数据库上下文 workspaceId={} bindingKey={}", id, key);
        return created;
    }

    synchronized void discardUnusedContext(String key) {
        ContextReference reference = contexts.get(key);
        if (reference != null && reference.references == 0 && reference.pool.physicalCount() == 0
                && contexts.remove(key, reference)) reference.pool.close();
        LOG.debug("释放未使用Workspace数据库上下文 workspaceId={} bindingKey={}", id, key);
    }

    synchronized void bind(EditorSession editor, SavedProfile profile, DatabaseContext context) {
        String editorId = editor.id().toString();
        String newKey = bindingKey(profile);
        SavedProfile previous = bindings.get(editorId);
        String oldKey = previous == null ? null : bindingKey(previous);
        boolean hadContext = editor.hasContext();
        if (newKey.equals(oldKey) && hadContext) return;
        if (editor.transactionDirty()) throw new ApiException("TRANSACTION_DECISION_REQUIRED", "切换链接前必须提交或回滚事务");
        ContextReference target = contexts.get(newKey);
        if (target == null || target.context != context) throw new IllegalStateException("Connection context is not registered");
        releaseEditorLease(editor, true);
        editors.bind(editor, context, newKey);
        if (!newKey.equals(oldKey) || !hadContext) target.references++;
        bindings.put(editorId, profile);
        if (hadContext) releaseContext(oldKey);
        emitConnectionState(editor, "ready", "链接已绑定，将在执行时借用数据库连接");
        LOG.info("编辑器绑定数据库链接 workspaceId={} editorId={} profileId={}", id, editorId, profile.profile().id());
    }

    synchronized void bindLogical(EditorSession editor, SavedProfile profile) {
        String editorId = editor.id().toString();
        SavedProfile previous = bindings.get(editorId);
        String oldKey = previous == null ? null : bindingKey(previous);
        String newKey = bindingKey(profile);
        if (editor.hasContext()) {
            if (editor.transactionDirty()) throw new ApiException("TRANSACTION_DECISION_REQUIRED", "切换链接前必须提交或回滚事务");
            releaseEditorLease(editor, true);
            editor.unbind();
            releaseContext(oldKey);
        } else if (oldKey != null && !oldKey.equals(newKey)) releaseContext(oldKey);
        bindings.put(editorId, profile);
        editor.bindLogical(newKey);
        emitConnectionState(editor, "credentials-required", "执行时需要数据库密码");
        LOG.info("编辑器建立逻辑数据库绑定 workspaceId={} editorId={} profileId={}", id, editorId,
                profile.profile().id());
    }

    synchronized void unbind(EditorSession editor) {
        String editorId = editor.id().toString();
        if (editor.transactionDirty()) throw new ApiException("TRANSACTION_DECISION_REQUIRED", "解绑前必须提交或回滚事务");
        SavedProfile previous = bindings.remove(editorId);
        releaseEditorLease(editor, true);
        editor.unbind();
        if (previous != null) releaseContext(bindingKey(previous));
        emitConnectionState(editor, "unbound", "已解除数据库链接");
        LOG.info("编辑器解除数据库绑定 workspaceId={} editorId={}", id, editorId);
    }

    SavedProfile binding(EditorSession editor) { return bindings.get(editor.id().toString()); }
    DatabaseContext requireEditorDatabase(EditorSession editor) { return editor.context(); }

    void ensureBound(EditorSession editor) {
        if (!editor.bound()) throw new ApiException("NOT_CONNECTED", "当前编辑标签尚未选择数据库链接");
        editor.touch();
    }

    UUID execute(final EditorSession editor, List<SqlStatement> statements, boolean stopOnError,
                 Consumer<UUID> started, QueryResultListener listener,
                 final EditorSessionRegistry.ExecutionCallback callback) {
        ensureBound(editor);
        if (editor.activeExecutionId() != null) throw new ApiException("QUERY_BUSY", "当前标签已有查询正在执行");
        if (editor.transactionOperationActive()) throw new ApiException("TRANSACTION_BUSY", "当前标签正在提交或回滚事务");
        LOG.info("Workspace开始执行SQL workspaceId={} editorId={} statements={} stopOnError={}", id,
                editor.id(), statements.size(), stopOnError);
        final ActiveLease active = acquireRunner(editor);
        try {
            return editors.execute(editor, statements, stopOnError, started, listener,
                    new EditorSessionRegistry.ExecutionCallback() {
                        @Override public void completed(UUID executionId, QueryExecution execution, Throwable failure) {
                            /*
                             * 浏览器可见的执行完成事件不能等待 JDBC 重置或 Catalog 恢复。
                             * 某些驱动归还连接时可能阻塞，若先清理会话，界面会在结果已经到达后仍停留在忙碌状态。
                             * 此时 runner 仍然挂在编辑器上，因此可以先报告最终事务状态，再保证租约清理。
                             */
                            try (LoggingContext ignored = LoggingContext.open(null, id, null,
                                    editor.id().toString(), executionId.toString())) {
                                try {
                                    callback.completed(executionId, execution, failure);
                                } finally {
                                    finishExecutionLease(editor, active);
                                    LOG.info("Workspace SQL执行回调完成 workspaceId={} editorId={} executionId={} failed={}",
                                            id, editor.id(), executionId,
                                            failure != null || (execution != null && execution.failed()));
                                }
                            }
                        }
                    });
        } catch (RuntimeException exception) {
            finishExecutionLease(editor, active); throw exception;
        }
    }

    CompletableFuture<PageResult> fetchPage(final EditorSession editor, final UUID executionId, String sql,
                                            int offset, int limit, final Runnable started) {
        ensureBound(editor);
        if (editor.activeExecutionId() != null) throw new ApiException("QUERY_BUSY", "当前标签已有查询正在执行");
        if (editor.transactionOperationActive()) throw new ApiException("TRANSACTION_BUSY", "当前标签正在提交或回滚事务");
        LOG.info("Workspace开始分页 workspaceId={} editorId={} executionId={} offset={} limit={}",
                id, editor.id(), executionId, offset, limit);
        final ActiveLease active = acquireRunner(editor);
        try {
            return active.runner.fetchPage(sql, offset, limit, () -> {
                if (!editor.beginExecution(executionId)) {
                    throw new ApiException(editor.transactionOperationActive() ? "TRANSACTION_BUSY" : "QUERY_BUSY",
                            editor.transactionOperationActive()
                                    ? "当前标签正在提交或回滚事务" : "当前标签已有查询正在执行");
                }
                try {
                    started.run();
                } catch (RuntimeException exception) {
                    editor.endExecution(executionId);
                    throw exception;
                }
            }).whenComplete((result, failure) -> {
                editor.endExecution(executionId);
                if (!active.runner.isTransactionDirty()) finishExecutionLease(editor, active);
            });
        } catch (RuntimeException exception) {
            editor.endExecution(executionId);
            finishExecutionLease(editor, active);
            throw exception;
        }
    }

    CompletableFuture<List<QueryRunner.RowChange>> applyResultChanges(final EditorSession editor,
                                                                      final ResultMutationTarget target,
                                                                      final List<List<String>> rows,
                                                                      final List<QueryRunner.RowChange> changes) {
        ensureBound(editor);
        if (editor.activeExecutionId() != null) throw new ApiException("QUERY_BUSY", "当前标签已有查询正在执行");
        if (editor.transactionOperationActive()) {
            throw new ApiException("TRANSACTION_BUSY", "当前标签正在提交或回滚事务");
        }
        final ActiveLease active;
        synchronized (this) {
            active = activeLeases.get(editor.id().toString());
        }
        if (active == null || !active.runner.isTransactionDirty()) {
            throw new ApiException("RESULT_EDIT_TRANSACTION_ENDED", "事务已经结束，请重新执行 FOR UPDATE");
        }
        return active.runner.applyResultChanges(target, rows, changes);
    }

    CompletableFuture<QueryRunner.MutationBatchResult> applyResultOperations(
            final EditorSession editor, final ResultMutationTarget target,
            final List<List<String>> rows, final List<List<String>> rowLocators,
            final List<QueryRunner.ResultOperation> operations) {
        ensureBound(editor);
        if (editor.activeExecutionId() != null) throw new ApiException("QUERY_BUSY", "当前标签已有查询正在执行");
        if (editor.transactionOperationActive()) {
            throw new ApiException("TRANSACTION_BUSY", "当前标签正在提交或回滚事务");
        }
        final ActiveLease active;
        synchronized (this) { active = activeLeases.get(editor.id().toString()); }
        if (active == null || !active.runner.isTransactionDirty()) {
            throw new ApiException("RESULT_EDIT_TRANSACTION_ENDED", "事务已经结束，请重新执行 FOR UPDATE");
        }
        return active.runner.applyResultOperations(target, rows, rowLocators, operations);
    }

    CompletableFuture<Void> commit(final EditorSession editor) {
        final ActiveLease active = beginTransactionOperation(editor);
        if (active == null) return CompletableFuture.completedFuture(null);
        try {
            return active.runner.commit().whenComplete((ignored, failure) -> {
                editor.endTransactionOperation();
                if (failure == null) {
                    editor.commitResultChanges();
                    removeLargeValueDrafts(editor.id().toString());
                    releasePinned(editor, active);
                }
                if (failure != null) LOG.warn("Workspace事务提交失败 workspaceId={} editorId={}", id, editor.id(), failure);
                else LOG.info("Workspace事务提交完成 workspaceId={} editorId={}", id, editor.id());
            });
        } catch (RuntimeException exception) {
            editor.endTransactionOperation();
            throw exception;
        }
    }

    CompletableFuture<Void> rollback(final EditorSession editor) {
        final ActiveLease active = beginTransactionOperation(editor);
        if (active == null) return CompletableFuture.completedFuture(null);
        try {
            return active.runner.rollback().whenComplete((ignored, failure) -> {
                editor.endTransactionOperation();
                if (failure == null) {
                    editor.rollbackResultChanges();
                    removeLargeValueDrafts(editor.id().toString());
                    releasePinned(editor, active);
                    LOG.info("Workspace事务回滚完成 workspaceId={} editorId={}", id, editor.id());
                } else {
                    LOG.warn("Workspace事务回滚失败 workspaceId={} editorId={}", id, editor.id(), failure);
                }
            });
        } catch (RuntimeException exception) {
            editor.endTransactionOperation();
            throw exception;
        }
    }

    private synchronized ActiveLease beginTransactionOperation(EditorSession editor) {
        ActiveLease active = activeLeases.get(editor.id().toString());
        if (active == null || !active.runner.isTransactionDirty()) return null;
        if (active.runner.isRunning()) {
            throw new ApiException("QUERY_BUSY", "SQL执行期间不能提交或回滚事务");
        }
        if (!editor.beginTransactionOperation()) {
            throw new ApiException(editor.activeExecutionId() == null ? "TRANSACTION_BUSY" : "QUERY_BUSY",
                    editor.activeExecutionId() == null ? "当前标签正在提交或回滚事务" : "SQL执行期间不能提交或回滚事务");
        }
        return active;
    }

    synchronized void browserDisconnected() {
        if (disconnected) return;
        disconnected = true;
        LOG.warn("Workspace进入浏览器断连状态 workspaceId={} editors={}", id, editors.all().size());
        for (EditorSession editor : editors.all()) {
            ActiveLease active = activeLeases.get(editor.id().toString());
            if (active != null && active.runner.isTransactionDirty()
                    && !active.pool.hasPinned(editor.id().toString())) active.pool.pin(editor.id().toString(), active.lease);
            if (editor.activeExecutionId() != null) try { editor.cancel(); } catch (RuntimeException ignored) { }
        }
        for (ContextReference reference : contexts.values()) reference.pool.retireUnpinned();
    }

    synchronized void browserConnected() {
        disconnected = false;
        LOG.info("Workspace恢复浏览器连接 workspaceId={}", id);
    }

    synchronized boolean hasTransactions() {
        for (ActiveLease active : activeLeases.values()) if (active.runner.isTransactionDirty()) return true;
        return false;
    }

    synchronized boolean hasDatabaseOperations() {
        if (!activeLeases.isEmpty()) return true;
        for (EditorSession editor : editors.all()) {
            if (editor.activeExecutionId() != null || editor.transactionOperationActive()) return true;
        }
        return false;
    }

    synchronized void setAutoCommit(boolean enabled) {
        if (autoCommit == enabled) return;
        if (hasTransactions()) {
            throw new ApiException("TRANSACTION_DECISION_REQUIRED", "存在未提交事务，请先提交或回滚后再切换自动提交");
        }
        if (hasDatabaseOperations()) {
            throw new ApiException("DATABASE_OPERATION_BUSY", "数据库操作正在进行，请完成后再切换自动提交");
        }
        for (ContextReference reference : contexts.values()) reference.pool.setAutoCommit(enabled);
        autoCommit = enabled;
        LOG.info("更新Workspace编辑器自动提交模式 workspaceId={} autoCommit={}", id, enabled);
    }

    synchronized int transactionCount() {
        int count = 0;
        for (ActiveLease active : activeLeases.values()) if (active.runner.isTransactionDirty()) count++;
        return count;
    }

    synchronized void rollbackDisconnectedTransactions() {
        for (EditorSession editor : new ArrayList<EditorSession>(editors.all())) {
            ActiveLease active = activeLeases.get(editor.id().toString());
            if (active == null || !active.runner.isTransactionDirty()) continue;
            try { active.runner.rollback().get(); }
            catch (Exception exception) {
                LOG.warn("断连事务回滚失败 workspaceId={} editorId={}", id, editor.id(), exception);
                active.pool.closePinned(editor.id().toString());
            }
            releasePinned(editor, active);
        }
    }

    void reapIdle(long cutoffMillis) {
        for (ContextReference reference : contexts.values()) reference.pool.reap(cutoffMillis);
        synchronized (this) {
            for (Map.Entry<String, ContextReference> entry : new ArrayList<Map.Entry<String, ContextReference>>(contexts.entrySet())) {
                ContextReference reference = entry.getValue();
                if (reference.references <= 0 && reference.pool.physicalCount() == 0
                        && contexts.remove(entry.getKey(), reference)) {
                    LOG.debug("回收空闲数据库上下文 workspaceId={} bindingKey={}", id, entry.getKey());
                    reference.pool.close();
                }
            }
        }
    }

    synchronized void closeEditor(String editorId) {
        EditorSession editor = editors.require(editorId);
        SavedProfile previous = bindings.remove(editorId);
        releaseEditorLease(editor, true);
        editors.close(editorId);
        if (previous != null) releaseContext(bindingKey(previous));
        LOG.info("关闭编辑器 workspaceId={} editorId={}", id, editorId);
    }

    void cachePassword(UUID profileId, char[] password) {
        char[] copied = java.util.Arrays.copyOf(password, password.length);
        char[] previous = credentials.put(profileId, copied);
        if (previous != null) java.util.Arrays.fill(previous, '\0');
    }

    char[] cachedPassword(UUID profileId) {
        char[] value = credentials.get(profileId);
        return value == null ? null : java.util.Arrays.copyOf(value, value.length);
    }

    static String bindingKey(SavedProfile profile) {
        return profile.profile().id().toString() + "@" + profile.revision();
    }

    private synchronized ActiveLease acquireRunner(EditorSession editor) {
        String editorId = editor.id().toString();
        ActiveLease existing = activeLeases.get(editorId);
        if (existing != null) return existing;
        ContextReference reference = contexts.get(editor.bindingKey());
        if (reference == null) throw new ApiException("NOT_CONNECTED", "数据库链接上下文不可用");
        try {
            WorkspaceJdbcPool.Lease lease = reference.pool.borrow();
            try {
                QueryRunner runner = new QueryRunner(lease.session(), editors.maxRows(), editors.streamBatchRows(),
                        reference.context.resultColumnResolver(), reference.context.provider().dialect(), false);
                editor.attachRunner(runner);
                ActiveLease created = new ActiveLease(reference.pool, lease, runner);
                activeLeases.put(editorId, created);
                LOG.debug("借用编辑器JDBC会话 workspaceId={} editorId={} bindingKey={}", id, editorId, editor.bindingKey());
                return created;
            } catch (RuntimeException exception) {
                reference.pool.release(lease); throw exception;
            }
        } catch (SQLException exception) {
            LOG.warn("借用编辑器JDBC会话失败 workspaceId={} editorId={} message={}", id, editorId,
                    safeMessage(exception), exception);
            throw new ApiException("CONNECTION_REOPEN_FAILED", "数据库连接失败：" + safeMessage(exception));
        }
    }

    private synchronized void finishExecutionLease(EditorSession editor, ActiveLease active) {
        String editorId = editor.id().toString();
        if (active.runner.isTransactionDirty()) {
            active.pool.pin(editorId, active.lease);
            emitConnectionState(editor, "ready", "事务连接已固定到当前编辑器");
            LOG.info("JDBC会话固定到事务 workspaceId={} editorId={}", id, editorId);
            return;
        }
        if (!activeLeases.remove(editorId, active)) return;
        editor.detachRunner(); active.runner.close(); active.pool.release(active.lease);
        emitConnectionState(editor, "ready", "数据库连接已归还队列");
        LOG.debug("JDBC会话归还队列 workspaceId={} editorId={}", id, editorId);
    }

    private synchronized void releasePinned(EditorSession editor, ActiveLease active) {
        String editorId = editor.id().toString();
        activeLeases.remove(editorId, active);
        editor.detachRunner(); active.runner.close(); active.pool.unpinAndRelease(editorId);
        emitConnectionState(editor, "ready", "事务已结束，数据库连接已归还队列");
        LOG.info("事务JDBC会话解除固定 workspaceId={} editorId={}", id, editorId);
    }

    private synchronized void releaseEditorLease(EditorSession editor, boolean rollback) {
        String editorId = editor.id().toString();
        ActiveLease active = activeLeases.remove(editorId);
        if (active == null) return;
        if (rollback) try { active.runner.rollback().get(); } catch (Exception ignored) { }
        editor.detachRunner(); active.runner.close();
        if (active.pool.hasPinned(editorId)) active.pool.unpinAndRelease(editorId);
        else active.pool.release(active.lease);
    }

    private void emitConnectionState(EditorSession editor, String state, String message) {
        events.emit("editor.connectionState", ApiPayloads.map("editorId", editor.id().toString(),
                "state", state, "message", message));
    }

    private void releaseContext(String key) {
        if (key == null) return;
        ContextReference reference = contexts.get(key);
        if (reference == null) return;
        reference.references--;
        if (reference.references <= 0 && reference.pool.physicalCount() == 0
                && contexts.remove(key, reference)) reference.pool.close();
    }

    Path storeUpload(String originalName, java.io.InputStream input) throws IOException {
        Files.createDirectories(temporaryDirectory);
        String uploadId = UUID.randomUUID().toString();
        String safeName = originalName == null ? "upload.csv" : originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = temporaryDirectory.resolve(uploadId + "-" + safeName);
        Files.copy(input, target); uploads.put(uploadId, target); return target;
    }

    String uploadId(Path path) {
        for (Map.Entry<String, Path> entry : uploads.entrySet()) if (entry.getValue().equals(path)) return entry.getKey();
        throw new ApiException("UPLOAD_NOT_FOUND", "上传文件不存在或已过期");
    }

    Path requireUpload(String uploadId) {
        Path path = uploads.get(uploadId);
        if (path == null || !Files.exists(path)) throw new ApiException("UPLOAD_NOT_FOUND", "上传文件不存在或已过期");
        return path;
    }

    void removeUpload(String uploadId) {
        Path path = uploads.remove(uploadId);
        if (path != null) try { Files.deleteIfExists(path); }
        catch (IOException exception) { LOG.warn("删除Workspace临时文件失败 workspaceId={} uploadId={}", id, uploadId, exception); }
    }

    String storeLargeValueDraft(String editorId, UUID executionId, int resultIndex, int columnIndex,
                                InputStream input, long maximumBytes) throws IOException {
        Files.createDirectories(temporaryDirectory);
        String token = UUID.randomUUID().toString();
        Path target = temporaryDirectory.resolve("result-large-value-" + token + ".draft");
        long written = 0;
        try (OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) {
                written += count;
                if (written > maximumBytes) throw new ApiException(
                        "RESULT_LOB_TOO_LARGE", "大字段草稿超过允许的最大大小");
                output.write(buffer, 0, count);
            }
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(target);
            throw exception;
        }
        largeValueDrafts.put(token, new LargeValueDraft(target, editorId, executionId,
                resultIndex, columnIndex, written));
        return token;
    }

    Path requireLargeValueDraft(String token, String editorId, UUID executionId,
                                int resultIndex, int columnIndex) {
        LargeValueDraft draft = largeValueDrafts.get(token);
        if (draft == null || !draft.editorId.equals(editorId) || !draft.executionId.equals(executionId)
                || draft.resultIndex != resultIndex || draft.columnIndex != columnIndex
                || !Files.exists(draft.path)) {
            throw new ApiException("RESULT_LOB_TOKEN_INVALID", "大字段草稿不存在、已过期或不属于当前结果");
        }
        return draft.path;
    }

    long largeValueDraftSize(String token) {
        LargeValueDraft draft = largeValueDrafts.get(token);
        return draft == null ? 0L : draft.size;
    }

    void removeLargeValueDraft(String token) {
        LargeValueDraft draft = largeValueDrafts.remove(token);
        if (draft != null) try { Files.deleteIfExists(draft.path); }
        catch (IOException exception) { LOG.warn("删除大字段草稿失败 workspaceId={} token={}", id, token, exception); }
    }

    void removeLargeValueDrafts(String editorId) {
        for (Map.Entry<String, LargeValueDraft> entry
                : new ArrayList<Map.Entry<String, LargeValueDraft>>(largeValueDrafts.entrySet())) {
            if (entry.getValue().editorId.equals(editorId)) removeLargeValueDraft(entry.getKey());
        }
    }

    CompletableFuture<Long> streamResultValue(EditorSession editor, ResultMutationTarget target,
                                               List<String> row, List<String> rowLocator,
                                               int columnIndex, OutputStream output, long maximumBytes) {
        ensureBound(editor);
        ActiveLease active;
        synchronized (this) { active = activeLeases.get(editor.id().toString()); }
        if (active == null || !active.runner.isTransactionDirty()) {
            throw new ApiException("RESULT_EDIT_TRANSACTION_ENDED", "事务已经结束，请重新执行 FOR UPDATE");
        }
        return active.runner.streamResultValue(target, row, rowLocator, columnIndex, output, maximumBytes);
    }

    private static final class LargeValueDraft {
        private final Path path;
        private final String editorId;
        private final UUID executionId;
        private final int resultIndex;
        private final int columnIndex;
        private final long size;
        private LargeValueDraft(Path path, String editorId, UUID executionId, int resultIndex,
                                int columnIndex, long size) {
            this.path = path; this.editorId = editorId; this.executionId = executionId;
            this.resultIndex = resultIndex; this.columnIndex = columnIndex; this.size = size;
        }
    }

    String startTask(final String kind, final TaskOperation operation) {
        final String taskId = UUID.randomUUID().toString(); activeTasks.incrementAndGet();
        LOG.info("Workspace任务开始 workspaceId={} taskId={} kind={}", id, taskId, kind);
        events.emit("task.started", ApiPayloads.map("taskId", taskId, "kind", kind));
        tasks.submit(new Runnable() {
            @Override public void run() {
                try {
                    Object result = operation.run(taskId);
                    events.emit("task.completed", ApiPayloads.map("taskId", taskId, "kind", kind, "result", result));
                } catch (Exception exception) {
                    LOG.warn("Workspace任务失败 workspaceId={} taskId={} kind={}", id, taskId, kind, exception);
                    events.emit("task.completed", ApiPayloads.map("taskId", taskId, "kind", kind,
                            "error", ApiPayloads.map("code", "TASK_FAILED", "message", safeMessage(exception))));
                } finally {
                    activeTasks.decrementAndGet();
                    LOG.info("Workspace任务结束 workspaceId={} taskId={} kind={}", id, taskId, kind);
                }
            }
        });
        return taskId;
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName()
                : SqlLogSupport.sanitizeMessage(message);
    }

    @Override public synchronized void close() {
        tasks.shutdownNow();
        for (EditorSession editor : new ArrayList<EditorSession>(editors.all())) releaseEditorLease(editor, true);
        editors.close();
        for (ContextReference reference : contexts.values()) reference.pool.close();
        contexts.clear(); bindings.clear(); largeValueDrafts.clear();
        for (char[] password : credentials.values()) java.util.Arrays.fill(password, '\0');
        credentials.clear(); events.close(); deleteTemporaryDirectory();
        LOG.info("Workspace运行时已关闭 workspaceId={}", id);
    }

    private void deleteTemporaryDirectory() {
        if (!Files.exists(temporaryDirectory)) return;
        try (Stream<Path> paths = Files.walk(temporaryDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) { LOG.warn("删除Workspace临时路径失败 workspaceId={} path={}", id, path, exception); }
            });
        } catch (IOException exception) { LOG.warn("扫描Workspace临时目录失败 workspaceId={}", id, exception); }
    }

    interface TaskOperation { Object run(String taskId) throws Exception; }

    private static final class ActiveLease {
        private final WorkspaceJdbcPool pool;
        private final WorkspaceJdbcPool.Lease lease;
        private final QueryRunner runner;
        private ActiveLease(WorkspaceJdbcPool pool, WorkspaceJdbcPool.Lease lease, QueryRunner runner) {
            this.pool=pool; this.lease=lease; this.runner=runner;
        }
    }

    private static final class ContextReference {
        private final DatabaseContext context;
        private final WorkspaceJdbcPool pool;
        private int references;
        private ContextReference(DatabaseContext context, WorkspaceJdbcPool pool) {
            this.context=context; this.pool=pool;
        }
    }
}
