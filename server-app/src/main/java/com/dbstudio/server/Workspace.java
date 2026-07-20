package com.dbstudio.server;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.query.QueryExecution;
import com.dbstudio.desktop.query.QueryResultListener;
import com.dbstudio.desktop.query.QueryRunner;
import com.dbstudio.desktop.query.QueryRunner.PageResult;
import com.dbstudio.desktop.web.EditorSessionRegistry;
import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import com.dbstudio.spi.SqlStatement;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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

/** Runtime state for one persistent workspace. Editors are logical; JDBC sessions come from queues. */
final class Workspace implements AutoCloseable {
    private final String id;
    private final EditorSessionRegistry editors;
    private final WorkspaceEventChannel events;
    private final Path temporaryDirectory;
    private final Map<String, Path> uploads = new ConcurrentHashMap<String, Path>();
    private final ExecutorService tasks;
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final EditorConnectionLimiter limiter;
    private final Map<String, ContextReference> contexts = new ConcurrentHashMap<String, ContextReference>();
    private final Map<String, SavedProfile> bindings = new ConcurrentHashMap<String, SavedProfile>();
    private final Map<String, ActiveLease> activeLeases = new ConcurrentHashMap<String, ActiveLease>();
    private final Map<UUID, char[]> credentials = new ConcurrentHashMap<UUID, char[]>();
    private volatile boolean disconnected;

    Workspace(String id, int maxRows, int streamBatchRows, ObjectMapper mapper, Path temporaryDirectory,
              EditorConnectionLimiter limiter) {
        this.id = id;
        this.editors = new EditorSessionRegistry(maxRows, streamBatchRows);
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
                    "transactionDirty", transaction,
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
                new WorkspaceJdbcPool(id + ":" + key, created, limiter));
        contexts.put(key, reference); return created;
    }

    synchronized void discardUnusedContext(String key) {
        ContextReference reference = contexts.get(key);
        if (reference != null && reference.references == 0 && reference.pool.physicalCount() == 0
                && contexts.remove(key, reference)) reference.pool.close();
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
    }

    synchronized void unbind(EditorSession editor) {
        String editorId = editor.id().toString();
        if (editor.transactionDirty()) throw new ApiException("TRANSACTION_DECISION_REQUIRED", "解绑前必须提交或回滚事务");
        SavedProfile previous = bindings.remove(editorId);
        releaseEditorLease(editor, true);
        editor.unbind();
        if (previous != null) releaseContext(bindingKey(previous));
        emitConnectionState(editor, "unbound", "已解除数据库链接");
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
        final ActiveLease active = acquireRunner(editor);
        try {
            return editors.execute(editor, statements, stopOnError, started, listener,
                    new EditorSessionRegistry.ExecutionCallback() {
                        @Override public void completed(UUID executionId, QueryExecution execution, Throwable failure) {
                            /*
                             * Completing the browser-visible execution must not wait for JDBC reset/catalog
                             * restoration. Some drivers may block while a lease is returned, which previously
                             * left the UI permanently busy even though every result event had already arrived.
                             * The runner is still attached here, so the callback can report the final transaction
                             * state. Lease cleanup is guaranteed afterwards.
                             */
                            try {
                                callback.completed(executionId, execution, failure);
                            } finally {
                                finishExecutionLease(editor, active);
                            }
                        }
                    });
        } catch (RuntimeException exception) {
            finishExecutionLease(editor, active); throw exception;
        }
    }

    CompletableFuture<PageResult> fetchPage(final EditorSession editor, String sql, int offset, int limit) {
        ensureBound(editor);
        if (editor.activeExecutionId() != null) throw new ApiException("QUERY_BUSY", "当前标签已有查询正在执行");
        final ActiveLease active = acquireRunner(editor);
        return active.runner.fetchPage(sql, offset, limit).whenComplete((result, failure) -> {
            if (!active.runner.isTransactionDirty()) finishExecutionLease(editor, active);
        });
    }

    CompletableFuture<Void> commit(final EditorSession editor) {
        ActiveLease active = activeLeases.get(editor.id().toString());
        if (active == null || !active.runner.isTransactionDirty()) return CompletableFuture.completedFuture(null);
        return active.runner.commit().whenComplete((ignored, failure) -> {
            if (failure == null) releasePinned(editor, active);
        });
    }

    CompletableFuture<Void> rollback(final EditorSession editor) {
        ActiveLease active = activeLeases.get(editor.id().toString());
        if (active == null) return CompletableFuture.completedFuture(null);
        return active.runner.rollback().whenComplete((ignored, failure) -> {
            if (failure == null) releasePinned(editor, active);
            else {
                synchronized (Workspace.this) {
                    String editorId = editor.id().toString();
                    activeLeases.remove(editorId, active);
                    editor.detachRunner(); active.runner.close(); active.pool.closePinned(editorId);
                }
            }
        });
    }

    synchronized void browserDisconnected() {
        if (disconnected) return;
        disconnected = true;
        for (EditorSession editor : editors.all()) {
            ActiveLease active = activeLeases.get(editor.id().toString());
            if (active != null && active.runner.isTransactionDirty()
                    && !active.pool.hasPinned(editor.id().toString())) active.pool.pin(editor.id().toString(), active.lease);
            if (editor.activeExecutionId() != null) try { editor.cancel(); } catch (RuntimeException ignored) { }
        }
        for (ContextReference reference : contexts.values()) reference.pool.retireUnpinned();
    }

    synchronized void browserConnected() { disconnected = false; }

    synchronized boolean hasTransactions() {
        for (ActiveLease active : activeLeases.values()) if (active.runner.isTransactionDirty()) return true;
        return false;
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
            catch (Exception ignored) { active.pool.closePinned(editor.id().toString()); }
            releasePinned(editor, active);
        }
    }

    void reapIdle(long cutoffMillis) {
        for (ContextReference reference : contexts.values()) reference.pool.reap(cutoffMillis);
        synchronized (this) {
            for (Map.Entry<String, ContextReference> entry : new ArrayList<Map.Entry<String, ContextReference>>(contexts.entrySet())) {
                ContextReference reference = entry.getValue();
                if (reference.references <= 0 && reference.pool.physicalCount() == 0
                        && contexts.remove(entry.getKey(), reference)) reference.pool.close();
            }
        }
    }

    synchronized void closeEditor(String editorId) {
        EditorSession editor = editors.require(editorId);
        SavedProfile previous = bindings.remove(editorId);
        releaseEditorLease(editor, true);
        editors.close(editorId);
        if (previous != null) releaseContext(bindingKey(previous));
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
                        reference.context.resultColumnResolver(), false);
                editor.attachRunner(runner);
                ActiveLease created = new ActiveLease(reference.pool, lease, runner);
                activeLeases.put(editorId, created); return created;
            } catch (RuntimeException exception) {
                reference.pool.release(lease); throw exception;
            }
        } catch (SQLException exception) {
            throw new ApiException("CONNECTION_REOPEN_FAILED", "数据库连接失败：" + safeMessage(exception));
        }
    }

    private synchronized void finishExecutionLease(EditorSession editor, ActiveLease active) {
        String editorId = editor.id().toString();
        if (active.runner.isTransactionDirty()) {
            active.pool.pin(editorId, active.lease);
            emitConnectionState(editor, "ready", "事务连接已固定到当前编辑器");
            return;
        }
        if (!activeLeases.remove(editorId, active)) return;
        editor.detachRunner(); active.runner.close(); active.pool.release(active.lease);
        emitConnectionState(editor, "ready", "数据库连接已归还队列");
    }

    private synchronized void releasePinned(EditorSession editor, ActiveLease active) {
        String editorId = editor.id().toString();
        activeLeases.remove(editorId, active);
        editor.detachRunner(); active.runner.close(); active.pool.unpinAndRelease(editorId);
        emitConnectionState(editor, "ready", "事务已结束，数据库连接已归还队列");
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
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    String startTask(final String kind, final TaskOperation operation) {
        final String taskId = UUID.randomUUID().toString(); activeTasks.incrementAndGet();
        tasks.submit(new Runnable() {
            @Override public void run() {
                try {
                    Object result = operation.run(taskId);
                    events.emit("task.completed", ApiPayloads.map("taskId", taskId, "kind", kind, "result", result));
                } catch (Exception exception) {
                    events.emit("task.completed", ApiPayloads.map("taskId", taskId, "kind", kind,
                            "error", ApiPayloads.map("code", "TASK_FAILED", "message", safeMessage(exception))));
                } finally { activeTasks.decrementAndGet(); }
            }
        });
        return taskId;
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }

    @Override public synchronized void close() {
        tasks.shutdownNow();
        for (EditorSession editor : new ArrayList<EditorSession>(editors.all())) releaseEditorLease(editor, true);
        editors.close();
        for (ContextReference reference : contexts.values()) reference.pool.close();
        contexts.clear(); bindings.clear();
        for (char[] password : credentials.values()) java.util.Arrays.fill(password, '\0');
        credentials.clear(); events.close(); deleteTemporaryDirectory();
    }

    private void deleteTemporaryDirectory() {
        if (!Files.exists(temporaryDirectory)) return;
        try (Stream<Path> paths = Files.walk(temporaryDirectory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
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
