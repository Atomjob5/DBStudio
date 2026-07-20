package com.dbstudio.server;

import com.dbstudio.desktop.persistence.WorkspaceRepository;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/** Distinguishes an explicit DBStudio exit from a process/browser crash. */
@Component
public final class ApplicationRunLifecycle {
    private final WorkspaceRepository repository;
    private final String runId = UUID.randomUUID().toString();
    private final AtomicBoolean normalExit = new AtomicBoolean();

    public ApplicationRunLifecycle(WorkspaceRepository repository) throws SQLException {
        this.repository = repository;
        repository.startRun(runId);
    }

    public String runId() { return runId; }
    public void requestNormalExit() { normalExit.set(true); }

    @PreDestroy
    public void close() {
        try { repository.finishRun(runId, normalExit.get()); }
        catch (SQLException ignored) { }
    }
}
