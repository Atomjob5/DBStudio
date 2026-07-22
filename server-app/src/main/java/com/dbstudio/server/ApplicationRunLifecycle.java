package com.dbstudio.server;

import com.dbstudio.desktop.persistence.WorkspaceRepository;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 区分用户主动退出与浏览器/进程异常终止，供 Workspace 恢复草稿判断使用。 */
@Component
public final class ApplicationRunLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationRunLifecycle.class);
    private final WorkspaceRepository repository;
    private final String runId = UUID.randomUUID().toString();
    private final AtomicBoolean normalExit = new AtomicBoolean();

    public ApplicationRunLifecycle(WorkspaceRepository repository) throws SQLException {
        this.repository = repository;
        repository.startRun(runId);
        LOG.info("应用运行实例已启动 runId={}", runId);
    }

    public String runId() { return runId; }
    public void requestNormalExit() {
        normalExit.set(true);
        LOG.info("收到应用正常退出请求 runId={}", runId);
    }

    @PreDestroy
    public void close() {
        try { repository.finishRun(runId, normalExit.get()); }
        catch (SQLException exception) { LOG.error("记录应用运行实例结束状态失败 runId={}", runId, exception); }
        LOG.info("应用运行实例已结束 runId={} normalExit={}", runId, normalExit.get());
    }
}
