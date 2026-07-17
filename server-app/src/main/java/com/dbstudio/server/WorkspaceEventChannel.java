package com.dbstudio.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

final class WorkspaceEventChannel implements AutoCloseable {
    private final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(128);
    private final Object monitor = new Object();
    private final ObjectMapper mapper;
    private final Thread sender;
    private volatile WebSocketSession session;
    private volatile boolean closed;

    WorkspaceEventChannel(ObjectMapper mapper, String workspaceId) {
        this.mapper = mapper;
        this.sender = new Thread(new Runnable() { @Override public void run() { sendLoop(); } },
                "dbstudio-events-" + workspaceId.substring(0, Math.min(8, workspaceId.length())));
        this.sender.setDaemon(true);
        this.sender.start();
    }

    void emit(String type, Object payload) {
        if (closed) return;
        try {
            String json = mapper.writeValueAsString(ApiPayloads.map("version", 1, "type", type, "payload", payload));
            queue.put(json);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode workspace event", exception);
        }
    }

    void attach(WebSocketSession value) {
        WebSocketSession previous = this.session;
        this.session = value;
        if (previous != null && previous != value) try { previous.close(); } catch (IOException ignored) { }
        synchronized (monitor) { monitor.notifyAll(); }
    }

    boolean detach(WebSocketSession value) {
        if (session == value) {
            session = null;
            return true;
        }
        return false;
    }

    private void sendLoop() {
        String pending = null;
        while (!closed) {
            try {
                WebSocketSession target = awaitSession();
                if (target == null) continue;
                if (pending == null) pending = queue.take();
                target.sendMessage(new TextMessage(pending));
                pending = null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException exception) {
                WebSocketSession failed = session;
                if (failed != null) detach(failed);
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
        if (current != null) try { current.close(); } catch (IOException ignored) { }
        sender.interrupt();
        synchronized (monitor) { monitor.notifyAll(); }
        queue.clear();
    }
}
