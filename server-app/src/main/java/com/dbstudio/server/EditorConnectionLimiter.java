package com.dbstudio.server;

import com.dbstudio.desktop.logging.SqlLogSupport;
import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 进程级物理编辑器 JDBC 会话准入控制器及任务管理器槽位目录。
 *
 * <p>槽位数量与最大活动会话设置一致，但槽位本身不会预先建立数据库连接。物理会话按需
 * 占用槽位；执行中或事务槽位在缩小上限后作为超额槽位保留，直到自然释放。</p>
 */
@org.springframework.stereotype.Component
public final class EditorConnectionLimiter {
    private static final Logger LOG = LoggerFactory.getLogger(EditorConnectionLimiter.class);
    private static final int EXECUTION_HISTORY_LIMIT = 10;
    private final Map<String, ActiveSession> active = new HashMap<String, ActiveSession>();
    private final List<Slot> slots = new ArrayList<Slot>();
    private int maximum = 10;
    private Runnable changedListener = new Runnable() { @Override public void run() { } };

    public EditorConnectionLimiter() { ensureConfiguredSlots(); }

    public synchronized void setChangedListener(Runnable listener) {
        changedListener = listener == null ? new Runnable() { @Override public void run() { } } : listener;
    }

    public synchronized void setMaximum(int value) {
        maximum = Math.max(1, Math.min(100, value));
        ensureConfiguredSlots();
        for (Slot slot : new ArrayList<Slot>(slots)) {
            slot.overLimit = slot.slotNumber > maximum;
            if (!slot.overLimit || !slot.assigned()) continue;
            ActiveSession session = active.get(slot.assignedKey);
            if (session != null && session.control.canSuspend() && session.control.suspend()) {
                active.remove(slot.assignedKey);
                removeOverflowSlot(slot);
                try { session.suspendedCallback.run(); } catch (RuntimeException ignored) { }
            }
        }
        for (Iterator<Slot> iterator = slots.iterator(); iterator.hasNext();) {
            Slot slot = iterator.next();
            if (slot.slotNumber > maximum && !slot.assigned()) iterator.remove();
        }
        ensureConfiguredSlots();
        signalChanged();
        LOG.info("设置物理编辑器JDBC会话上限 maximum={} visibleSlots={}", maximum, slots.size());
    }

    public synchronized int maximum() { return maximum; }
    public synchronized int activeCount() { return active.size(); }
    public synchronized int overLimitCount() {
        int count = 0;
        for (Slot slot : slots) if (slot.overLimit && slot.assigned()) count++;
        return count;
    }

    public synchronized boolean acquire(String key, EditorSession editor, Runnable suspendedCallback) {
        return acquire(key, new SessionControl() {
            @Override public boolean canSuspend() { return editor.canSuspend(); }
            @Override public boolean suspend() { return editor.suspend(); }
            @Override public long lastTouched() { return editor.lastTouched(); }
        }, suspendedCallback);
    }

    synchronized boolean acquire(String key, SessionControl control, Runnable suspendedCallback) {
        ActiveSession existing = active.get(key);
        if (existing != null) { existing.touched = System.currentTimeMillis(); return true; }
        if (active.size() >= maximum) evictOldestSafe();
        if (active.size() >= maximum) {
            LOG.warn("物理编辑器JDBC会话达到上限 key={} active={} maximum={}", key, active.size(), maximum);
            return false;
        }
        Slot slot = availableSlot();
        if (slot == null) {
            LOG.warn("没有可用JDBC槽位 key={} active={} maximum={}", key, active.size(), maximum);
            return false;
        }
        ActiveSession created = new ActiveSession(control, suspendedCallback, slot);
        active.put(key, created);
        slot.assignedKey = key;
        slot.connectionId = null;
        slot.overLimit = false;
        slot.stateVersion++;
        signalChanged();
        LOG.debug("申请物理编辑器JDBC会话 key={} slot={} active={}", key, slot.slotNumber, active.size());
        return true;
    }

    synchronized String slotId(String key) {
        ActiveSession session = active.get(key);
        if (session == null) throw new IllegalStateException("JDBC slot is not active");
        return session.slot.slotId;
    }

    synchronized void attachConnection(String key, String connectionId) {
        ActiveSession session = active.get(key);
        if (session == null) return;
        session.slot.connectionId = connectionId;
        session.slot.stateVersion++;
        signalChanged();
    }

    synchronized void connectionChanged(String key, String connectionId) {
        ActiveSession session = active.get(key);
        if (session == null) return;
        session.slot.connectionId = connectionId;
        session.slot.stateVersion++;
        signalChanged();
    }

    public synchronized void touch(String key) {
        ActiveSession value = active.get(key);
        if (value != null) value.touched = System.currentTimeMillis();
    }

    public synchronized void release(String key) {
        ActiveSession removed = active.remove(key);
        if (removed == null) return;
        Slot slot = removed.slot;
        slot.assignedKey = null;
        slot.connectionId = null;
        slot.stateVersion++;
        if (slot.overLimit || slot.slotNumber > maximum) removeOverflowSlot(slot);
        signalChanged();
        LOG.debug("释放物理编辑器JDBC会话 key={} active={}", key, active.size());
    }

    synchronized Target target(String slotId, long expectedVersion) {
        Slot slot = requireSlot(slotId);
        if (slot.stateVersion != expectedVersion) {
            throw new ApiException("JDBC_CONNECTION_STATE_CHANGED", "连接状态已经变化，请刷新后重新确认");
        }
        if (!slot.assigned() || slot.connectionId == null) {
            throw new ApiException("JDBC_CONNECTION_NOT_FOUND", "该线程当前没有物理 JDBC 连接");
        }
        return new Target(slot.slotId, slot.assignedKey, slot.connectionId);
    }

    synchronized void startExecution(String slotId, ExecutionSnapshot execution) {
        Slot slot = findSlot(slotId);
        if (slot == null || !slot.assigned()) return;
        for (Iterator<ExecutionSnapshot> iterator = slot.executions.iterator(); iterator.hasNext();) {
            if (iterator.next().executionId.equals(execution.executionId)) iterator.remove();
        }
        slot.executions.add(0, execution);
        while (slot.executions.size() > EXECUTION_HISTORY_LIMIT) {
            slot.executions.remove(slot.executions.size() - 1);
        }
        slot.stateVersion++;
        signalChanged();
    }

    synchronized void completeExecution(String slotId, String executionId, String status,
                                        long completedAt, long durationMs, long rowCount, String message) {
        Slot slot = findSlot(slotId);
        if (slot == null) return;
        ExecutionSnapshot execution = execution(slot, executionId);
        if (execution == null || !"running".equals(execution.status)) return;
        execution.status = status;
        execution.completedAt = completedAt;
        execution.durationMs = durationMs;
        execution.rowCount = rowCount;
        execution.message = sanitize(message);
        slot.stateVersion++;
        signalChanged();
    }

    synchronized List<SlotSnapshot> snapshots() {
        List<SlotSnapshot> result = new ArrayList<SlotSnapshot>();
        for (Slot slot : slots) result.add(snapshot(slot));
        Collections.sort(result, new Comparator<SlotSnapshot>() {
            @Override public int compare(SlotSnapshot left, SlotSnapshot right) {
                return Integer.compare(left.slotNumber, right.slotNumber);
            }
        });
        return result;
    }

    synchronized List<ExecutionSnapshot> executions(String slotId) {
        Slot slot = requireSlot(slotId);
        List<ExecutionSnapshot> result = new ArrayList<ExecutionSnapshot>();
        for (ExecutionSnapshot execution : slot.executions) result.add(execution.copy(false));
        return result;
    }

    synchronized ExecutionSnapshot executionDetail(String slotId, String executionId) {
        ExecutionSnapshot value = execution(requireSlot(slotId), executionId);
        if (value == null) throw new ApiException("JDBC_EXECUTION_NOT_FOUND", "SQL 执行记录不存在或已经过期");
        return value.copy(true);
    }

    synchronized int clearExpiredData() {
        int cleared = 0;
        for (Slot slot : slots) {
            for (Iterator<ExecutionSnapshot> iterator = slot.executions.iterator(); iterator.hasNext();) {
                if (!"running".equals(iterator.next().status)) { iterator.remove(); cleared++; }
            }
            slot.stateVersion++;
        }
        signalChanged();
        return cleared;
    }

    private void evictOldestSafe() {
        String candidateKey = null;
        ActiveSession candidate = null;
        for (Map.Entry<String, ActiveSession> entry : active.entrySet()) {
            ActiveSession value = entry.getValue();
            if (!value.control.canSuspend()) continue;
            if (candidate == null || value.control.lastTouched() < candidate.control.lastTouched()) {
                candidateKey = entry.getKey(); candidate = value;
            }
        }
        if (candidate == null || !candidate.control.suspend()) {
            LOG.debug("没有可安全暂停的物理JDBC会话 active={} maximum={}", active.size(), maximum);
            return;
        }
        active.remove(candidateKey);
        candidate.slot.assignedKey = null;
        candidate.slot.connectionId = null;
        candidate.slot.stateVersion++;
        if (candidate.slot.overLimit) removeOverflowSlot(candidate.slot);
        LOG.info("为新会话暂停最久未使用JDBC会话 key={} active={}", candidateKey, active.size());
        try { candidate.suspendedCallback.run(); } catch (RuntimeException ignored) { }
        signalChanged();
    }

    private Slot availableSlot() {
        for (Slot slot : slots) {
            if (slot.slotNumber <= maximum && !slot.assigned()) return slot;
        }
        return null;
    }

    private void ensureConfiguredSlots() {
        for (int number = 1; number <= maximum; number++) {
            if (slotNumber(number) == null) slots.add(new Slot(number));
        }
        Collections.sort(slots, new Comparator<Slot>() {
            @Override public int compare(Slot left, Slot right) {
                return Integer.compare(left.slotNumber, right.slotNumber);
            }
        });
    }

    private Slot slotNumber(int number) {
        for (Slot slot : slots) if (slot.slotNumber == number) return slot;
        return null;
    }

    private Slot findSlot(String slotId) {
        for (Slot slot : slots) if (slot.slotId.equals(slotId)) return slot;
        return null;
    }

    private Slot requireSlot(String slotId) {
        Slot slot = findSlot(slotId);
        if (slot == null) throw new ApiException("JDBC_SLOT_NOT_FOUND", "JDBC 连接线程不存在");
        return slot;
    }

    private void removeOverflowSlot(Slot slot) {
        if (!slot.assigned() && slot.slotNumber > maximum) slots.remove(slot);
    }

    private static ExecutionSnapshot execution(Slot slot, String executionId) {
        for (ExecutionSnapshot execution : slot.executions) {
            if (execution.executionId.equals(executionId)) return execution;
        }
        return null;
    }

    private SlotSnapshot snapshot(Slot slot) {
        ExecutionSnapshot recent = slot.executions.isEmpty() ? null : slot.executions.get(0);
        return new SlotSnapshot(slot.slotId, slot.slotNumber, slot.stateVersion, slot.assigned(),
                slot.overLimit, slot.connectionId, slot.executions.size(), recent);
    }

    private void signalChanged() {
        try { changedListener.run(); } catch (RuntimeException ignored) { }
    }

    private static String sanitize(String message) {
        return message == null || message.trim().isEmpty() ? null : SqlLogSupport.sanitizeMessage(message);
    }

    interface SessionControl {
        boolean canSuspend();
        boolean suspend();
        long lastTouched();
    }

    static final class Target {
        final String slotId, permitKey, connectionId;
        private Target(String slotId, String permitKey, String connectionId) {
            this.slotId=slotId; this.permitKey=permitKey; this.connectionId=connectionId;
        }
    }

    static final class SlotSnapshot {
        final String slotId, connectionId;
        final int slotNumber, historyCount;
        final long stateVersion;
        final boolean assigned, overLimit;
        final ExecutionSnapshot lastExecution;
        private SlotSnapshot(String slotId, int slotNumber, long stateVersion, boolean assigned,
                             boolean overLimit, String connectionId, int historyCount,
                             ExecutionSnapshot lastExecution) {
            this.slotId=slotId; this.slotNumber=slotNumber; this.stateVersion=stateVersion;
            this.assigned=assigned; this.overLimit=overLimit; this.connectionId=connectionId;
            this.historyCount=historyCount;
            this.lastExecution=lastExecution == null ? null : lastExecution.copy(false);
        }
    }

    static final class ExecutionSnapshot {
        final String executionId, workspaceId, workspaceName, editorId, editorTitle;
        final String profileId, profileName, providerId, databaseName, schemaName, sql;
        final long startedAt;
        long completedAt, durationMs, rowCount;
        String status, message;

        ExecutionSnapshot(String executionId, String workspaceId, String workspaceName,
                          String editorId, String editorTitle, String profileId, String profileName,
                          String providerId, String databaseName, String schemaName, String sql,
                          long startedAt) {
            this.executionId=executionId; this.workspaceId=workspaceId; this.workspaceName=workspaceName;
            this.editorId=editorId; this.editorTitle=editorTitle; this.profileId=profileId;
            this.profileName=profileName; this.providerId=providerId; this.databaseName=databaseName;
            this.schemaName=schemaName; this.sql=sql; this.startedAt=startedAt; this.status="running";
        }

        private ExecutionSnapshot copy(boolean includeSql) {
            ExecutionSnapshot copy = new ExecutionSnapshot(executionId, workspaceId, workspaceName,
                    editorId, editorTitle, profileId, profileName, providerId, databaseName,
                    schemaName, includeSql ? sql : null, startedAt);
            copy.completedAt=completedAt; copy.durationMs=durationMs; copy.rowCount=rowCount;
            copy.status=status; copy.message=message; return copy;
        }
    }

    private static final class Slot {
        private final String slotId = UUID.randomUUID().toString();
        private final int slotNumber;
        private final List<ExecutionSnapshot> executions = new ArrayList<ExecutionSnapshot>();
        private long stateVersion = 1L;
        private String assignedKey, connectionId;
        private boolean overLimit;
        private Slot(int slotNumber) { this.slotNumber=slotNumber; }
        private boolean assigned() { return assignedKey != null; }
    }

    private static final class ActiveSession {
        private final SessionControl control;
        private final Runnable suspendedCallback;
        private final Slot slot;
        private long touched = System.currentTimeMillis();
        private ActiveSession(SessionControl control, Runnable suspendedCallback, Slot slot) {
            this.control=control; this.suspendedCallback=suspendedCallback; this.slot=slot;
        }
    }
}
