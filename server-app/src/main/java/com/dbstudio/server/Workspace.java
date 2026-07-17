package com.dbstudio.server;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.csv.CsvService;
import com.dbstudio.desktop.web.EditorSessionRegistry;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private volatile DatabaseContext database;

    Workspace(String id, int maxRows, int streamBatchRows, ObjectMapper mapper, Path temporaryDirectory) {
        this.id = id;
        this.editors = new EditorSessionRegistry(maxRows, streamBatchRows);
        this.events = new WorkspaceEventChannel(mapper, id);
        this.temporaryDirectory = temporaryDirectory;
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

    synchronized void connect(DatabaseContext context) {
        editors.close();
        DatabaseContext previous = database;
        database = context;
        if (previous != null) previous.close();
    }

    synchronized void disconnect() {
        editors.close();
        DatabaseContext previous = database;
        database = null;
        if (previous != null) previous.close();
    }

    DatabaseContext requireDatabase() {
        DatabaseContext current = database;
        if (current == null) throw new ApiException("NOT_CONNECTED", "请先连接数据库");
        return current;
    }

    ConnectionProfile connectedProfile() {
        DatabaseContext current = database;
        return current == null ? null : current.profile();
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
        editors.close();
        if (database != null) { database.close(); database = null; }
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
}
