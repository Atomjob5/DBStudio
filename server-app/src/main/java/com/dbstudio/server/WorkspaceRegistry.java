package com.dbstudio.server;

import com.dbstudio.desktop.logging.SqlLogSupport;
import com.dbstudio.desktop.AppDirectories;
import com.dbstudio.desktop.persistence.SettingsRepository;
import com.dbstudio.desktop.persistence.WorkspaceRepository;
import com.dbstudio.desktop.persistence.WorkspaceRepository.WorkspaceRecord;
import com.dbstudio.desktop.query.QueryRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 工作空间目录、进程内运行时和浏览器独占关系的统一协调器。
 *
 * <p>目录数据保存到 SQLite，运行时对象只保存在当前进程。一个 Workspace 同时只允许
 * 一个健康浏览器持有 owner；WebSocket 断开时运行时仍会保留，以便事务保护和异常恢复。</p>
 */
@org.springframework.stereotype.Component
public final class WorkspaceRegistry implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceRegistry.class);
    private final Map<String, Workspace> runtimes = new ConcurrentHashMap<String, Workspace>();
    private final Map<String, String> owners = new ConcurrentHashMap<String, String>();
    private final Map<String, ScheduledFuture<?>> transactionExpiry =
            new ConcurrentHashMap<String, ScheduledFuture<?>>();
    private final ScheduledExecutorService scheduler;
    private final ObjectMapper mapper;
    private final SettingsRepository settings;
    private final WorkspaceRepository repository;
    private final MachineIdentity machineIdentity;
    private final ApplicationRunLifecycle runLifecycle;
    private final EditorConnectionLimiter limiter;
    private volatile int idleTimeoutMinutes;
    private volatile int transactionRollbackMinutes;
    private volatile boolean autoCommit;

    public WorkspaceRegistry(ObjectMapper mapper, SettingsRepository settings, WorkspaceRepository repository,
                             MachineIdentity machineIdentity, ApplicationRunLifecycle runLifecycle,
                             EditorConnectionLimiter limiter) {
        this.mapper=mapper; this.settings=settings; this.repository=repository;
        this.machineIdentity=machineIdentity; this.runLifecycle=runLifecycle; this.limiter=limiter;
        limiter.setMaximum(configuredInt("connection.maxActiveSessions", 10, 1, 100));
        limiter.setChangedListener(new Runnable() {
            @Override public void run() {
                broadcast("jdbc.connections.changed", ApiPayloads.map("updatedAt", System.currentTimeMillis()));
            }
        });
        idleTimeoutMinutes = configuredInt("connection.idleTimeoutMinutes", 10, 1, 1_440);
        autoCommit = configuredBoolean("connection.autoCommit", false);
        transactionRollbackMinutes = configuredInt(
                "connection.transactionDisconnectRollbackMinutes", 10, 1, 1_440);
        scheduler = Executors.newScheduledThreadPool(2, new ThreadFactory() {
            private int sequence;
            @Override public synchronized Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "dbstudio-workspace-" + (++sequence));
                thread.setDaemon(true); return thread;
            }
        });
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override public void run() { reapIdleConnections(); }
        }, 1L, 1L, TimeUnit.MINUTES);
        LOG.info("Workspace运行时已启动 maxSessions={} autoCommit={} idleTimeoutMinutes={} transactionRollbackMinutes={}",
                limiter.maximum(), autoCommit, idleTimeoutMinutes, transactionRollbackMinutes);
    }

    List<WorkspaceRecord> catalog() throws SQLException { return repository.findAll(); }

    WorkspaceRecord createCatalog(String name) throws SQLException {
        String localUuid = UUID.randomUUID().toString();
        WorkspaceRecord created = repository.create(machineIdentity.workspaceId(localUuid), localUuid,
                machineIdentity.fingerprint(), name);
        LOG.info("创建Workspace workspaceId={} name={}", created.id(), created.name());
        return created;
    }

    WorkspaceRecord renameCatalog(String id, String name) throws SQLException {
        validateId(id);
        WorkspaceRecord renamed = repository.rename(id, name);
        Workspace runtime = runtimes.get(id);
        if (runtime != null) runtime.setName(renamed.name());
        LOG.info("重命名Workspace workspaceId={} name={}", id, name);
        return renamed;
    }

    synchronized void deleteCatalog(String id) throws SQLException {
        validateId(id);
        Workspace runtime = runtimes.get(id);
        if (runtime != null && (runtime.events().connected() || runtime.hasTransactions())) {
            throw new ApiException("WORKSPACE_IN_USE", "工作空间正在使用或仍有受保护事务，不能删除");
        }
        owners.remove(id); cancelTransactionExpiry(id);
        runtime = runtimes.remove(id);
        if (runtime != null) runtime.close();
        repository.softDelete(id);
        LOG.info("删除Workspace workspaceId={}", id);
    }

    synchronized WorkspaceOpen open(String id, String clientId, boolean reconnect) throws SQLException {
        validateId(id); validateClientId(clientId);
        WorkspaceRecord record = repository.find(id).orElseThrow(
                () -> new ApiException("WORKSPACE_NOT_FOUND", "工作空间不存在或已删除"));
        Workspace runtime = runtime(id);
        String currentOwner = owners.get(id);
        boolean sameOwner = clientId.equals(currentOwner);
        if (!sameOwner && currentOwner != null && runtime.events().connected()) {
            throw new ApiException("WORKSPACE_IN_USE", "该工作空间已在另一个窗口中打开");
        }
        owners.put(id, clientId); repository.opened(id);
        boolean recovery = !(reconnect && sameOwner) && (record.dirtyCount() > 0 || record.transactionCount() > 0
                || runtime.hasTransactions());
        boolean processRestarted = recoveryWasCreatedByEarlierRun(id);
        LOG.info("打开Workspace workspaceId={} clientId={} recoveryRequired={} processRestarted={}", id,
                clientId, recovery, processRestarted);
        return new WorkspaceOpen(runtime, record, recovery, processRestarted);
    }

    boolean recoveryWasCreatedByEarlierRun(String id) throws SQLException {
        for (WorkspaceRepository.EditorDraft draft : repository.recoveryDrafts(id)) {
            if (!runLifecycle.runId().equals(draft.runId())) return true;
        }
        return false;
    }

    /** 工作空间选择页逐步迁移期间，为旧客户端保留的兼容创建入口。 */
    WorkspaceRegistration create(String id) {
        validateId(id);
        try {
            if (!repository.find(id).isPresent()) repository.create(id, id, machineIdentity.fingerprint(),
                    "工作空间 " + id.substring(0, 8));
            Workspace existing = runtimes.get(id);
            Workspace runtime = runtime(id);
            return new WorkspaceRegistration(runtime, existing == null);
        } catch (SQLException exception) {
            throw new ApiException("WORKSPACE_STORE_FAILED", SqlLogSupport.sanitizeMessage(exception.getMessage()), exception);
        }
    }

    Workspace require(String id) {
        validateId(id);
        Workspace workspace = runtimes.get(id);
        if (workspace == null) throw new ApiException("WORKSPACE_NOT_OPEN", "工作空间尚未打开");
        return workspace;
    }

    Workspace requireOwned(String id, String clientId) {
        Workspace workspace = require(id);
        if (clientId == null || !clientId.equals(owners.get(id))) {
            throw new ApiException("WORKSPACE_OWNERSHIP_REQUIRED", "当前窗口没有占用该工作空间");
        }
        return workspace;
    }

    synchronized void browserConnected(String id, String clientId) {
        if (!clientId.equals(owners.get(id))) throw new ApiException("WORKSPACE_OWNERSHIP_REQUIRED", "当前窗口没有占用该工作空间");
        cancelTransactionExpiry(id); require(id).browserConnected();
        LOG.info("Workspace事件通道已连接 workspaceId={} clientId={}", id, clientId);
    }

    synchronized void browserDisconnected(String id, String clientId) {
        if (!clientId.equals(owners.get(id))) return;
        Workspace workspace = runtimes.get(id);
        if (workspace == null || workspace.events().connected()) return;
        workspace.browserDisconnected();
        LOG.warn("Workspace浏览器断开 workspaceId={} hasTransactions={}", id, workspace.hasTransactions());
        if (workspace.hasTransactions()) scheduleTransactionExpiry(id, workspace);
    }

    synchronized void closeClient(String id, String clientId) {
        if (!clientId.equals(owners.get(id))) return;
        Workspace workspace = runtimes.get(id);
        if (workspace != null && workspace.events().connected()) workspace.events().closeSession();
        owners.remove(id);
        LOG.info("Workspace客户端主动关闭 workspaceId={} clientId={}", id, clientId);
    }

    synchronized void expireNow(String id) {
        cancelTransactionExpiry(id); owners.remove(id);
        Workspace workspace = runtimes.remove(id);
        if (workspace != null) workspace.close();
        LOG.warn("Workspace运行时已过期 workspaceId={}", id);
    }

    void setMaxRows(int maxRows) {
        int bounded=Math.max(1,Math.min(100_000,maxRows));
        for (Workspace workspace:runtimes.values()) workspace.editors().setMaxRows(bounded);
    }
    void setStreamBatchRows(int rows) {
        int bounded=Math.max(1,Math.min(1_000,rows));
        for (Workspace workspace:runtimes.values()) workspace.editors().setStreamBatchRows(bounded);
    }
    void setClobMaxCharacters(int characters) {
        int bounded = Math.max(QueryRunner.MIN_CLOB_MAX_CHARACTERS,
                Math.min(QueryRunner.MAX_CLOB_MAX_CHARACTERS, characters));
        for (Workspace workspace : runtimes.values()) workspace.editors().setClobMaxCharacters(bounded);
    }
    void clearRiskConfirmations() {
        for (Workspace workspace : runtimes.values()) workspace.clearRiskConfirmations();
    }
    void setMaxActiveSessions(int maximum) {
        limiter.setMaximum(maximum);
        LOG.info("更新最大活动JDBC会话数 maximum={}", limiter.maximum());
    }

    Map<String, Object> jdbcConnectionSlots() {
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (EditorConnectionLimiter.SlotSnapshot slot : limiter.snapshots()) values.add(jdbcSlotPayload(slot));
        return ApiPayloads.map("maximum", limiter.maximum(), "activeCount", limiter.activeCount(),
                "overLimitCount", limiter.overLimitCount(), "slots", values,
                "generatedAt", System.currentTimeMillis());
    }

    List<Map<String, Object>> jdbcSlotExecutions(String slotId) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (EditorConnectionLimiter.ExecutionSnapshot execution : limiter.executions(slotId)) {
            result.add(jdbcExecutionPayload(execution, false));
        }
        return result;
    }

    Map<String, Object> jdbcSlotExecution(String slotId, String executionId) {
        return jdbcExecutionPayload(limiter.executionDetail(slotId, executionId), true);
    }

    Map<String, Object> probeJdbcSlot(String slotId, long stateVersion) throws Exception {
        EditorConnectionLimiter.Target target = limiter.target(slotId, stateVersion);
        JdbcOwner owner = jdbcOwner(target.connectionId);
        owner.workspace.probeJdbcConnection(target.connectionId,
                ((Number) owner.connection.get("stateVersion")).longValue());
        return jdbcSlot(slotId);
    }

    Map<String, Object> abortJdbcSlot(String slotId, long stateVersion) {
        EditorConnectionLimiter.Target target = limiter.target(slotId, stateVersion);
        JdbcOwner owner = jdbcOwner(target.connectionId);
        Map<String, Object> aborted = owner.workspace.abortJdbcConnection(target.connectionId,
                ((Number) owner.connection.get("stateVersion")).longValue());
        Map<String, Object> result = jdbcSlot(slotId);
        result.put("transactionLost", aborted.get("transactionLost"));
        result.put("resultChangesLost", aborted.get("resultChangesLost"));
        result.put("workspaceId", aborted.get("workspaceId"));
        result.put("editorId", aborted.get("editorId"));
        return result;
    }

    Map<String, Object> clearExpiredJdbcData() {
        int histories = limiter.clearExpiredData();
        for (Workspace workspace : runtimes.values()) workspace.clearRecentJdbcConnections();
        return ApiPayloads.map("clearedExecutions", histories, "slots", jdbcConnectionSlots().get("slots"));
    }

    private Map<String, Object> jdbcSlot(String slotId) {
        for (EditorConnectionLimiter.SlotSnapshot slot : limiter.snapshots()) {
            if (slot.slotId.equals(slotId)) return jdbcSlotPayload(slot);
        }
        throw new ApiException("JDBC_SLOT_NOT_FOUND", "JDBC 连接槽位不存在");
    }

    private Map<String, Object> jdbcSlotPayload(EditorConnectionLimiter.SlotSnapshot slot) {
        Map<String, Object> connection = jdbcConnectionForSlot(slot.slotId);
        EditorConnectionLimiter.ExecutionSnapshot recent = slot.lastExecution;
        boolean physical = connection != null && Boolean.TRUE.equals(connection.get("physicalConnected"));
        String state = connection == null ? "idle" : String.valueOf(connection.get("state"));
        Map<String, Object> result = ApiPayloads.map(
                "slotId", slot.slotId, "slotNumber", slot.slotNumber,
                "stateVersion", slot.stateVersion, "state", state,
                "physicalConnected", physical, "overLimit", slot.overLimit,
                "historyCount", slot.historyCount,
                "connectionId", connection == null ? null : connection.get("connectionId"),
                "profileId", value(connection, "profileId", recent == null ? null : recent.profileId),
                "profileName", value(connection, "profileName", recent == null ? null : recent.profileName),
                "providerId", value(connection, "providerId", recent == null ? null : recent.providerId),
                "databaseName", value(connection, "databaseName", recent == null ? null : recent.databaseName),
                "schemaName", value(connection, "schemaName", recent == null ? null : recent.schemaName),
                "workspaceId", value(connection, "workspaceId", recent == null ? null : recent.workspaceId),
                "workspaceName", value(connection, "workspaceName", recent == null ? null : recent.workspaceName),
                "editorId", value(connection, "editorId", recent == null ? null : recent.editorId),
                "editorTitle", value(connection, "editorTitle", recent == null ? null : recent.editorTitle),
                "executionId", connection == null ? null : connection.get("executionId"),
                "transactionDirty", connection != null && Boolean.TRUE.equals(connection.get("transactionDirty")),
                "transactionOperationActive", connection != null
                        && Boolean.TRUE.equals(connection.get("transactionOperationActive")),
                "createdAt", connection == null ? null : connection.get("createdAt"),
                "lastActiveAt", connection == null ? null : connection.get("lastActiveAt"),
                "lastExecutionAt", recent == null ? null : recent.startedAt,
                "lastProbeLatencyMs", connection == null ? null : connection.get("lastProbeLatencyMs"),
                "disconnectedAt", connection == null ? null : connection.get("disconnectedAt"),
                "message", connection == null ? null : connection.get("message"));
        return result;
    }

    private Map<String, Object> jdbcConnectionForSlot(String slotId) {
        Map<String, Object> selected = null;
        for (Workspace workspace : runtimes.values()) {
            for (Map<String, Object> connection : workspace.jdbcConnections()) {
                if (!slotId.equals(connection.get("slotId"))) continue;
                boolean connected = Boolean.TRUE.equals(connection.get("physicalConnected"));
                boolean selectedConnected = selected != null
                        && Boolean.TRUE.equals(selected.get("physicalConnected"));
                if (selected == null || connected && !selectedConnected
                        || !connected && !selectedConnected
                        && jdbcConnectionTimestamp(connection) > jdbcConnectionTimestamp(selected)) {
                    selected = connection;
                }
            }
        }
        return selected;
    }

    private static long jdbcConnectionTimestamp(Map<String, Object> connection) {
        Object disconnectedAt = connection.get("disconnectedAt");
        if (disconnectedAt instanceof Number) return ((Number) disconnectedAt).longValue();
        Object lastActiveAt = connection.get("lastActiveAt");
        return lastActiveAt instanceof Number ? ((Number) lastActiveAt).longValue() : 0L;
    }

    private JdbcOwner jdbcOwner(String connectionId) {
        for (Workspace workspace : runtimes.values()) {
            for (Map<String, Object> connection : workspace.jdbcConnections()) {
                if (connectionId.equals(connection.get("connectionId"))
                        && Boolean.TRUE.equals(connection.get("physicalConnected"))) {
                    return new JdbcOwner(workspace, connection);
                }
            }
        }
        throw new ApiException("JDBC_CONNECTION_NOT_FOUND", "JDBC 连接不存在或已经断开");
    }

    private static Object value(Map<String, Object> source, String key, Object fallback) {
        if (source == null) return fallback;
        Object value = source.get(key);
        return value == null || String.valueOf(value).isEmpty() ? fallback : value;
    }

    private static Map<String, Object> jdbcExecutionPayload(
            EditorConnectionLimiter.ExecutionSnapshot execution, boolean includeSql) {
        return ApiPayloads.map("executionId", execution.executionId,
                "workspaceId", execution.workspaceId, "workspaceName", execution.workspaceName,
                "editorId", execution.editorId, "editorTitle", execution.editorTitle,
                "profileId", execution.profileId, "profileName", execution.profileName,
                "providerId", execution.providerId, "databaseName", execution.databaseName,
                "schemaName", execution.schemaName, "startedAt", execution.startedAt,
                "completedAt", execution.completedAt == 0L ? null : execution.completedAt,
                "durationMs", execution.completedAt == 0L ? null : execution.durationMs,
                "status", execution.status, "rowCount", execution.rowCount,
                "message", execution.message, "sql", includeSql ? execution.sql : null);
    }
    synchronized void setAutoCommit(boolean enabled) {
        if (autoCommit == enabled) return;
        for (Workspace workspace : runtimes.values()) {
            if (workspace.hasTransactions()) {
                throw new ApiException("TRANSACTION_DECISION_REQUIRED", "存在未提交事务，请先提交或回滚后再切换自动提交");
            }
        }
        for (Workspace workspace : runtimes.values()) {
            if (workspace.hasDatabaseOperations()) {
                throw new ApiException("DATABASE_OPERATION_BUSY", "数据库操作正在进行，请完成后再切换自动提交");
            }
        }
        for (Workspace workspace : runtimes.values()) workspace.setAutoCommit(enabled);
        autoCommit = enabled;
        LOG.info("更新编辑器自动提交模式 autoCommit={} workspaces={}", enabled, runtimes.size());
    }
    void setIdleTimeoutMinutes(int minutes) {
        idleTimeoutMinutes=Math.max(1,Math.min(1_440,minutes));
        LOG.info("更新JDBC空闲回收时间 minutes={}", idleTimeoutMinutes);
    }
    void setTransactionRollbackMinutes(int minutes) {
        transactionRollbackMinutes=Math.max(1,Math.min(1_440,minutes));
        LOG.info("更新事务断连保护时间 minutes={}", transactionRollbackMinutes);
    }

    void broadcast(String type,Object payload) {
        for (Workspace workspace:runtimes.values()) workspace.events().emit(type,payload);
    }

    boolean ownedBy(String id,String clientId) { return clientId != null && clientId.equals(owners.get(id)); }
    String owner(String id) { return owners.get(id); }
    Workspace runtimeIfPresent(String id) { return runtimes.get(id); }
    Collection<Workspace> openRuntimes() {
        return Collections.unmodifiableCollection(new ArrayList<Workspace>(runtimes.values()));
    }

    private Workspace runtime(final String id) {
        Workspace existing=runtimes.get(id); if(existing!=null)return existing;
        String workspaceName = id;
        try {
            java.util.Optional<WorkspaceRecord> record = repository.find(id);
            if (record.isPresent()) workspaceName = record.get().name();
        } catch (SQLException exception) {
            LOG.debug("读取Workspace名称失败 workspaceId={}", id, exception);
        }
        Workspace created=new Workspace(id,workspaceName,
                configuredInt("result.maxRows",QueryRunner.DEFAULT_MAX_ROWS,1,100_000),
                configuredInt("result.streamBatchRows",QueryRunner.DEFAULT_STREAM_BATCH_ROWS,1,1_000),
                configuredInt("result.clobMaxCharacters", QueryRunner.DEFAULT_CLOB_MAX_CHARACTERS,
                        QueryRunner.MIN_CLOB_MAX_CHARACTERS, QueryRunner.MAX_CLOB_MAX_CHARACTERS),
                autoCommit,mapper,
                AppDirectories.dataDirectory().resolve("tmp").resolve(id),limiter);
        created.events().onDisconnected(new WorkspaceEventChannel.DisconnectListener() {
            @Override public void disconnected(String clientId) { browserDisconnected(id,clientId); }
        });
        Workspace raced=runtimes.putIfAbsent(id,created);
        if(raced!=null){created.close();return raced;} return created;
    }

    private void reapIdleConnections() {
        long cutoff=System.currentTimeMillis()-TimeUnit.MINUTES.toMillis(idleTimeoutMinutes);
        for(Workspace workspace:runtimes.values()) {
            try { workspace.reapIdle(cutoff); }
            catch (RuntimeException exception) { LOG.warn("回收Workspace空闲连接失败 workspaceId={}", workspace.id(), exception); }
        }
    }

    private synchronized void scheduleTransactionExpiry(final String id,final Workspace workspace) {
        cancelTransactionExpiry(id);
        ScheduledFuture<?> task=scheduler.schedule(new Runnable(){@Override public void run(){
            transactionExpiry.remove(id);
            if(!workspace.events().connected()&&workspace.hasTransactions()){
                workspace.rollbackDisconnectedTransactions();
                LOG.warn("Workspace断连事务超过保护时间，自动回滚 workspaceId={}", id);
                workspace.events().emit("transaction.autoRolledBack",ApiPayloads.map(
                        "workspaceId",id,"message","断连事务已超过保护时间并自动回滚"));
            }
        }},transactionRollbackMinutes,TimeUnit.MINUTES);
        transactionExpiry.put(id,task);
    }

    private void cancelTransactionExpiry(String id) {
        ScheduledFuture<?> task=transactionExpiry.remove(id); if(task!=null)task.cancel(false);
    }

    private int configuredInt(String key,int fallback,int minimum,int maximum) {
        try{return Math.max(minimum,Math.min(maximum,Integer.parseInt(settings.get(key).orElse(String.valueOf(fallback)))));}
        catch(Exception ignored){return fallback;}
    }

    private boolean configuredBoolean(String key, boolean fallback) {
        try {
            String value = settings.get(key).orElse(String.valueOf(fallback));
            return "true".equals(value) ? true : "false".equals(value) ? false : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static void validateId(String id) {
        try{UUID.fromString(id);}catch(Exception exception){throw new ApiException("INVALID_WORKSPACE_ID","Workspace ID 无效");}
    }
    private static void validateClientId(String id) {
        try{UUID.fromString(id);}catch(Exception exception){throw new ApiException("INVALID_CLIENT_ID","浏览器窗口 ID 无效");}
    }

    @Override public void close() {
        scheduler.shutdownNow();
        for(ScheduledFuture<?> task:transactionExpiry.values())task.cancel(false);
        transactionExpiry.clear();
        for(Workspace workspace:new ArrayList<Workspace>(runtimes.values()))workspace.close();
        runtimes.clear();owners.clear();
    }

    static final class WorkspaceOpen {
        private final Workspace workspace; private final WorkspaceRecord record;
        private final boolean recoveryRequired,processRestarted;
        private WorkspaceOpen(Workspace workspace,WorkspaceRecord record,boolean recoveryRequired,boolean processRestarted){
            this.workspace=workspace;this.record=record;this.recoveryRequired=recoveryRequired;this.processRestarted=processRestarted;
        }
        Workspace workspace(){return workspace;} WorkspaceRecord record(){return record;}
        boolean recoveryRequired(){return recoveryRequired;} boolean processRestarted(){return processRestarted;}
    }

    static final class WorkspaceRegistration {
        private final Workspace workspace;private final boolean created;
        private WorkspaceRegistration(Workspace workspace,boolean created){this.workspace=workspace;this.created=created;}
        Workspace workspace(){return workspace;} boolean created(){return created;}
    }

    private static final class JdbcOwner {
        private final Workspace workspace;
        private final Map<String, Object> connection;
        private JdbcOwner(Workspace workspace, Map<String, Object> connection) {
            this.workspace=workspace; this.connection=connection;
        }
    }
}
