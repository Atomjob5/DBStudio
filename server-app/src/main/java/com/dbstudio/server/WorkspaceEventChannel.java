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

final class WorkspaceEventChannel implements AutoCloseable {
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(128);
    private final Object monitor = new Object();
    private final ObjectMapper mapper;
    private final Thread sender;
    private volatile WebSocketSession session;
    private volatile String clientId;
    private volatile long lastPongAt;
    private volatile boolean closed;
    private volatile DisconnectListener disconnected = new DisconnectListener() {
        @Override public void disconnected(String ignored) { }
    };

    WorkspaceEventChannel(ObjectMapper mapper, String workspaceId) {
        this.mapper = mapper;
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
        if (previous != null && previous != value) try { previous.close(); } catch (IOException ignored) { }
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
                /* Resolve the socket after an event is available. Otherwise the sender can retain
                 * a closed browser tab while waiting on an empty queue and deliver the replacement
                 * socket's workspace.ready event to that stale session. */
                target = awaitSession();
                if (target == null) continue;
                synchronized (target) { target.sendMessage(new TextMessage(pending)); }
                pending = null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | IllegalStateException exception) {
                /* A new socket may already be attached. Detach only the socket on which this send
                 * failed, never whichever socket happens to be current now. */
                if (target != null) detach(target);
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
