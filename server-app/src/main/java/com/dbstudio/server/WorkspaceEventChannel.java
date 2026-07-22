package com.dbstudio.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Workspace 的有界事件通道。
 *
 * <p>业务线程只负责把事件放入队列，单独发送线程按顺序写入当前 WebSocket。新连接替换旧连接时，
 * 发送失败只能摘除失败的旧 Socket，不能误伤随后建立的新 Socket。</p>
 */
final class WorkspaceEventChannel implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceEventChannel.class);
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(128);
    private final Object monitor = new Object();
    private final ObjectMapper mapper;
    private final Thread sender;
    private final String workspaceId;
    private volatile WebSocketSession session;
    private volatile String clientId;
    private volatile long lastPongAt;
    private volatile boolean closed;
    private volatile DisconnectListener disconnected = new DisconnectListener() {
        @Override public void disconnected(String ignored) { }
    };

    WorkspaceEventChannel(ObjectMapper mapper, String workspaceId) {
        this.mapper = mapper;
        this.workspaceId = workspaceId;
        this.sender = new Thread(new Runnable() { @Override public void run() { sendLoop(); } },
                "dbstudio-events-" + workspaceId.substring(0, Math.min(8, workspaceId.length())));
        this.sender.setDaemon(true);
        this.sender.start();
    }

    void emit(String type, Object payload) {
        if (closed) return;
        if (!connected() && transientEvent(type)) return;
        try {
            String json = mapper.writeValueAsString(ApiPayloads.map("version", 1, "type", type, "payload", payload));
            if (queue.remainingCapacity() == 0) {
                LOG.warn("Workspace事件队列已满，发送线程正在等待 workspaceId={} type={}", workspaceId, type);
            }
            queue.put(json);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode workspace event", exception);
        }
    }

    void emitImmediate(String type, Object payload) throws IOException {
        WebSocketSession current = session;
        if (current == null || !current.isOpen()) throw new IOException("Workspace event channel is not connected");
        String json = mapper.writeValueAsString(ApiPayloads.map("version", 1, "type", type, "payload", payload));
        synchronized (current) { current.sendMessage(new TextMessage(json)); }
    }

    private static boolean transientEvent(String type) {
        return type != null && (type.startsWith("query.") || type.startsWith("task.")
                || "metadata.completionProgress".equals(type));
    }

    synchronized void attach(WebSocketSession value, String ownerClientId) {
        WebSocketSession previous = this.session;
        this.session = value;
        this.clientId = ownerClientId;
        this.lastPongAt = System.currentTimeMillis();
        if (previous != null && previous != value) {
            LOG.info("WebSocket替换旧连接 workspaceId={} oldSession={} newSession={}", workspaceId,
                    previous.getId(), value.getId());
            try { previous.close(); } catch (IOException exception) {
                LOG.debug("关闭旧WebSocket失败 workspaceId={}", workspaceId, exception);
            }
        }
        synchronized (monitor) { monitor.notifyAll(); }
    }

    void onDisconnected(DisconnectListener listener) {
        disconnected = listener == null ? new DisconnectListener() {
            @Override public void disconnected(String ignored) { }
        } : listener;
    }

    boolean connected() {
        WebSocketSession current = session;
        return current != null && current.isOpen();
    }

    String connectedClientId() { return connected() ? clientId : null; }

    void pong() { lastPongAt = System.currentTimeMillis(); }

    boolean stale(long now, long timeoutMillis) {
        return connected() && now - lastPongAt > timeoutMillis;
    }

    void ping() throws IOException {
        WebSocketSession current = session;
        if (current == null || !current.isOpen()) return;
        synchronized (current) { current.sendMessage(new PingMessage(ByteBuffer.wrap(new byte[] { 1 }))); }
    }

    synchronized void closeSession() {
        WebSocketSession current = session;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }

    synchronized boolean detach(WebSocketSession value) {
        if (session == value) {
            String detachedClient = clientId;
            session = null;
            clientId = null;
            queue.clear();
            disconnected.disconnected(detachedClient);
            return true;
        }
        return false;
    }

    private void sendLoop() {
        String pending = null;
        while (!closed) {
            WebSocketSession target = null;
            try {
                if (pending == null) pending = queue.take();
                /* 只有队列确实有事件后才解析 Socket。否则发送线程可能在空队列等待期间保留
                 * 已关闭的浏览器标签，并把新连接的 workspace.ready 错发给旧会话。 */
                target = awaitSession();
                if (target == null) continue;
                try (LoggingContext ignored = LoggingContext.open(null, workspaceId, clientId, null, null)) {
                    synchronized (target) { target.sendMessage(new TextMessage(pending)); }
                }
                pending = null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | IllegalStateException exception) {
                /* 此时可能已经挂载了新 Socket；只摘除本次发送失败的 Socket，不能摘除当前新连接。 */
                if (target != null) {
                    LOG.warn("Workspace事件发送失败 workspaceId={} session={}", workspaceId, target.getId(), exception);
                    detach(target);
                }
            }
        }
    }

    private WebSocketSession awaitSession() throws InterruptedException {
        synchronized (monitor) {
            while (!closed && (session == null || !session.isOpen())) monitor.wait();
            return session;
        }
    }

    @Override public void close() {
        closed = true;
        WebSocketSession current = session;
        session = null;
        clientId = null;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
        sender.interrupt();
        synchronized (monitor) { monitor.notifyAll(); }
        queue.clear();
    }

    interface DisconnectListener { void disconnected(String clientId); }
}
