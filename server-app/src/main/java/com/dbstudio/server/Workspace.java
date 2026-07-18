package com.dbstudio.server;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.web.EditorSessionRegistry;
import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
    private final Map<UUID, char[]> credentials = new ConcurrentHashMap<UUID, char[]>();

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
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    String id() { return id; }
    EditorSessionRegistry editors() { return editors; }
    WorkspaceEventChannel events() { return events; }
    int activeTasks() { return activeTasks.get(); }

    String permitKey(EditorSession editor) { return id + ":" + editor.id(); }

    synchronized DatabaseContext context(String key) {
        ContextReference reference = contexts.get(key);
        return reference == null ? null : reference.context;
    }

    synchronized DatabaseContext registerContext(String key, DatabaseContext created) {
        ContextReference existing = contexts.get(key);
        if (existing != null) { created.close(); return existing.context; }
        contexts.put(key, new ContextReference(created));
        return created;
    }

    synchronized void discardUnusedContext(String key) {
        ContextReference reference = contexts.get(key);
        if (reference != null && reference.references == 0 && contexts.remove(key, reference)) reference.context.close();
    }

    synchronized void bind(EditorSession editor, SavedProfile profile, DatabaseContext context) {
        String editorId = editor.id().toString();
        String newKey = bindingKey(profile);
        SavedProfile previous = bindings.get(editorId);
        String oldKey = previous == null ? null : bindingKey(previous);
        if (newKey.equals(oldKey)) return;
        ContextReference target = contexts.get(newKey);
        if (target == null) throw new IllegalStateException("Connection context is not registered");
        editors.bind(editor, context, newKey);
        releasePermit(editor);
        target.references++;
        bindings.put(editorId, profile);
        releaseContext(oldKey);
        emitConnectionState(editor, "suspended", "链接已绑定，将在使用时建立会话");
    }

    synchronized void unbind(EditorSession editor) {
        String editorId = editor.id().toString();
        SavedProfile previous = bindings.get(editorId);
        editor.unbind();
        releasePermit(editor);
        bindings.remove(editorId);
        if (previous != null) releaseContext(bindingKey(previous));
        emitConnectionState(editor, "unbound", "已解除数据库链接");
    }

    SavedProfile binding(EditorSession editor) { return bindings.get(editor.id().toString()); }
    DatabaseContext requireEditorDatabase(EditorSession editor) { return editor.context(); }

    void ensureActive(final EditorSession editor) {
        if (editor.active()) { editor.touch(); limiter.touch(permitKey(editor)); return; }
        if (!editor.bound()) throw new ApiException("NOT_CONNECTED", "当前编辑标签尚未选择数据库链接");
        final String key = permitKey(editor);
        if (!limiter.acquire(key, editor, new Runnable() {
            @Override public void run() { emitConnectionState(editor, "suspended", "空闲会话已释放"); }
        })) throw new ApiException("CONNECTION_LIMIT_REACHED", "已达到最大活动链接数，请关闭空闲标签或处理未提交事务");
        try {
            editors.activate(editor);
            emitConnectionState(editor, "active", "数据库会话已激活");
        } catch (SQLException exception) {
            limiter.release(key);
            emitConnectionState(editor, "suspended", "数据库会话重连失败");
            throw new ApiException("CONNECTION_REOPEN_FAILED", "数据库会话重连失败：" + safeMessage(exception));
        }
    }

    void suspendIdle(long cutoffMillis) {
        for (EditorSession editor : editors.all()) {
            if (editor.active() && editor.lastTouched() <= cutoffMillis && editor.suspend()) {
                limiter.release(permitKey(editor));
                emitConnectionState(editor, "suspended", "空闲会话已安全释放");
            }
        }
        suspendUnusedMetadataConnections();
    }

    private synchronized void suspendUnusedMetadataConnections() {
        for (Map.Entry<String, ContextReference> entry : contexts.entrySet()) {
            boolean active = false;
            for (EditorSession editor : editors.all()) {
                if (editor.active() && entry.getKey().equals(editor.bindingKey())) { active = true; break; }
            }
            if (!active) entry.getValue().context.suspendMetadata();
        }
    }

    synchronized void closeEditor(String editorId) {
        EditorSession editor = editors.require(editorId);
        SavedProfile previous = bindings.remove(editorId);
        releasePermit(editor);
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

    private void releasePermit(EditorSession editor) { limiter.release(permitKey(editor)); }

    private void emitConnectionState(EditorSession editor, String state, String message) {
        events.emit("editor.connectionState", ApiPayloads.map("editorId", editor.id().toString(),
                "state", state, "message", message));
    }

    private void releaseContext(String key) {
        if (key == null) return;
        ContextReference reference = contexts.get(key);
        if (reference == null) return;
        reference.references--;
        if (reference.references <= 0 && contexts.remove(key, reference)) reference.context.close();
    }

    Path storeUpload(String originalName, java.io.InputStream input) throws IOException {
        Files.createDirectories(temporaryDirectory);
        String id = UUID.randomUUID().toString();
        String safeName = originalName == null ? "upload.csv" : originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = temporaryDirectory.resolve(id + "-" + safeName);
        Files.copy(input, target);
        uploads.put(id, target);
        return target;
    }

    String uploadId(Path path) {
        for (Map.Entry<String, Path> entry : uploads.entrySet()) if (entry.getValue().equals(path)) return entry.getKey();
        throw new ApiException("UPLOAD_NOT_FOUND", "上传文件不存在或已过期");
    }

    Path requireUpload(String id) {
        Path path = uploads.get(id);
        if (path == null || !Files.exists(path)) throw new ApiException("UPLOAD_NOT_FOUND", "上传文件不存在或已过期");
        return path;
    }

    void removeUpload(String id) {
        Path path = uploads.remove(id);
        if (path != null) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    String startTask(final String kind, final TaskOperation operation) {
        final String taskId = UUID.randomUUID().toString();
        activeTasks.incrementAndGet();
        tasks.submit(new Runnable() {
            @Override public void run() {
                try {
                    Object result = operation.run(taskId);
                    events.emit("task.completed", ApiPayloads.map(
                            "taskId", taskId, "kind", kind, "result", result));
                } catch (Exception exception) {
                    events.emit("task.completed", ApiPayloads.map("taskId", taskId, "kind", kind,
                            "error", ApiPayloads.map("code", "TASK_FAILED", "message", safeMessage(exception))));
                } finally {
                    activeTasks.decrementAndGet();
                }
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
        for (EditorSession editor : editors.all()) limiter.release(permitKey(editor));
        editors.close();
        for (ContextReference reference : contexts.values()) reference.context.close();
        contexts.clear(); bindings.clear();
        for (char[] password : credentials.values()) java.util.Arrays.fill(password, '\0');
        credentials.clear();
        events.close();
        deleteTemporaryDirectory();
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

    private static final class ContextReference {
        private final DatabaseContext context;
        private int references;
        private ContextReference(DatabaseContext context) { this.context = context; }
    }
}
