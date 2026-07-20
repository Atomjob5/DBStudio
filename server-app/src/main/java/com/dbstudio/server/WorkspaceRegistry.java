package com.dbstudio.server;

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

/** Persistent workspace catalog plus in-process runtimes and exclusive browser ownership. */
@org.springframework.stereotype.Component
public final class WorkspaceRegistry implements AutoCloseable {
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

    public WorkspaceRegistry(ObjectMapper mapper, SettingsRepository settings, WorkspaceRepository repository,
                             MachineIdentity machineIdentity, ApplicationRunLifecycle runLifecycle,
                             EditorConnectionLimiter limiter) {
        this.mapper=mapper; this.settings=settings; this.repository=repository;
        this.machineIdentity=machineIdentity; this.runLifecycle=runLifecycle; this.limiter=limiter;
        limiter.setMaximum(configuredInt("connection.maxActiveSessions", 10, 1, 100));
        idleTimeoutMinutes = configuredInt("connection.idleTimeoutMinutes", 10, 1, 1_440);
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
    }

    List<WorkspaceRecord> catalog() throws SQLException { return repository.findAll(); }

    WorkspaceRecord createCatalog(String name) throws SQLException {
        String localUuid = UUID.randomUUID().toString();
        return repository.create(machineIdentity.workspaceId(localUuid), localUuid,
                machineIdentity.fingerprint(), name);
    }

    WorkspaceRecord renameCatalog(String id, String name) throws SQLException {
        validateId(id); return repository.rename(id, name);
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
        return new WorkspaceOpen(runtime, record, recovery, processRestarted);
    }

    boolean recoveryWasCreatedByEarlierRun(String id) throws SQLException {
        for (WorkspaceRepository.EditorDraft draft : repository.recoveryDrafts(id)) {
            if (!runLifecycle.runId().equals(draft.runId())) return true;
        }
        return false;
    }

    /** Compatibility entry used by older tests and clients while the chooser endpoint is adopted. */
    WorkspaceRegistration create(String id) {
        validateId(id);
        try {
            if (!repository.find(id).isPresent()) repository.create(id, id, machineIdentity.fingerprint(),
                    "工作空间 " + id.substring(0, 8));
            Workspace existing = runtimes.get(id);
            Workspace runtime = runtime(id);
            return new WorkspaceRegistration(runtime, existing == null);
        } catch (SQLException exception) { throw new ApiException("WORKSPACE_STORE_FAILED", exception.getMessage(), exception); }
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
    }

    synchronized void browserDisconnected(String id, String clientId) {
        if (!clientId.equals(owners.get(id))) return;
        Workspace workspace = runtimes.get(id);
        if (workspace == null || workspace.events().connected()) return;
        workspace.browserDisconnected();
        if (workspace.hasTransactions()) scheduleTransactionExpiry(id, workspace);
    }

    synchronized void closeClient(String id, String clientId) {
        if (!clientId.equals(owners.get(id))) return;
        Workspace workspace = runtimes.get(id);
        if (workspace != null && workspace.events().connected()) workspace.events().closeSession();
        owners.remove(id);
    }

    synchronized void expireNow(String id) {
        cancelTransactionExpiry(id); owners.remove(id);
        Workspace workspace = runtimes.remove(id);
        if (workspace != null) workspace.close();
    }

    void setMaxRows(int maxRows) {
        int bounded=Math.max(1,Math.min(100_000,maxRows));
        for (Workspace workspace:runtimes.values()) workspace.editors().setMaxRows(bounded);
    }
    void setStreamBatchRows(int rows) {
        int bounded=Math.max(1,Math.min(1_000,rows));
        for (Workspace workspace:runtimes.values()) workspace.editors().setStreamBatchRows(bounded);
    }
    void setMaxActiveSessions(int maximum) { limiter.setMaximum(maximum); }
    void setIdleTimeoutMinutes(int minutes) { idleTimeoutMinutes=Math.max(1,Math.min(1_440,minutes)); }
    void setTransactionRollbackMinutes(int minutes) {
        transactionRollbackMinutes=Math.max(1,Math.min(1_440,minutes));
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
        Workspace created=new Workspace(id,configuredInt("result.maxRows",QueryRunner.DEFAULT_MAX_ROWS,1,100_000),
                configuredInt("result.streamBatchRows",QueryRunner.DEFAULT_STREAM_BATCH_ROWS,1,1_000),mapper,
                AppDirectories.dataDirectory().resolve("tmp").resolve(id),limiter);
        created.events().onDisconnected(new WorkspaceEventChannel.DisconnectListener() {
            @Override public void disconnected(String clientId) { browserDisconnected(id,clientId); }
        });
        Workspace raced=runtimes.putIfAbsent(id,created);
        if(raced!=null){created.close();return raced;} return created;
    }

    private void reapIdleConnections() {
        long cutoff=System.currentTimeMillis()-TimeUnit.MINUTES.toMillis(idleTimeoutMinutes);
        for(Workspace workspace:runtimes.values()) workspace.reapIdle(cutoff);
    }

    private synchronized void scheduleTransactionExpiry(final String id,final Workspace workspace) {
        cancelTransactionExpiry(id);
        ScheduledFuture<?> task=scheduler.schedule(new Runnable(){@Override public void run(){
            transactionExpiry.remove(id);
            if(!workspace.events().connected()&&workspace.hasTransactions()){
                workspace.rollbackDisconnectedTransactions();
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
}
