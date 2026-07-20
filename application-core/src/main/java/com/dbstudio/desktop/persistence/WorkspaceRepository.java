package com.dbstudio.desktop.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Persistent workspace catalog and crash-recovery journal. */
public final class WorkspaceRepository {
    private final Connection connection;

    public WorkspaceRepository(AppDatabase database) {
        this.connection = database.connection();
    }

    public synchronized List<WorkspaceRecord> findAll() throws SQLException {
        List<WorkspaceRecord> result = new ArrayList<WorkspaceRecord>();
        String sql = "SELECT w.id,w.local_uuid,w.machine_fingerprint,w.name,w.created_at,w.updated_at,"
                + "w.last_opened_at,COUNT(CASE WHEN r.dirty=1 THEN 1 END) AS dirty_count,"
                + "COUNT(CASE WHEN r.transaction_state<>'none' THEN 1 END) AS transaction_count "
                + "FROM workspace_catalog w LEFT JOIN workspace_editor_recovery r ON r.workspace_id=w.id "
                + "WHERE w.deleted_at IS NULL GROUP BY w.id ORDER BY "
                + "CASE WHEN w.last_opened_at IS NULL THEN 1 ELSE 0 END,w.last_opened_at DESC,w.name COLLATE NOCASE";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(workspace(rows));
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized Optional<WorkspaceRecord> find(String id) throws SQLException {
        String sql = "SELECT w.id,w.local_uuid,w.machine_fingerprint,w.name,w.created_at,w.updated_at,"
                + "w.last_opened_at,COUNT(CASE WHEN r.dirty=1 THEN 1 END) AS dirty_count,"
                + "COUNT(CASE WHEN r.transaction_state<>'none' THEN 1 END) AS transaction_count "
                + "FROM workspace_catalog w LEFT JOIN workspace_editor_recovery r ON r.workspace_id=w.id "
                + "WHERE w.id=? AND w.deleted_at IS NULL GROUP BY w.id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(workspace(rows)) : Optional.<WorkspaceRecord>empty();
            }
        }
    }

    public synchronized WorkspaceRecord create(String id, String localUuid, String machineFingerprint,
                                                String name) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO workspace_catalog(id,local_uuid,machine_fingerprint,name,created_at,updated_at) "
                        + "VALUES(?,?,?,?,?,?)")) {
            statement.setString(1, id); statement.setString(2, localUuid);
            statement.setString(3, machineFingerprint); statement.setString(4, normalizedName(name));
            statement.setString(5, now); statement.setString(6, now); statement.executeUpdate();
        }
        return find(id).get();
    }

    public synchronized WorkspaceRecord rename(String id, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE workspace_catalog SET name=?,updated_at=? WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1, normalizedName(name)); statement.setString(2, Instant.now().toString());
            statement.setString(3, id);
            if (statement.executeUpdate() == 0) throw new SQLException("Workspace not found: " + id);
        }
        return find(id).get();
    }

    public synchronized void softDelete(String id) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE workspace_catalog SET deleted_at=?,updated_at=? WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1, now); statement.setString(2, now); statement.setString(3, id);
            if (statement.executeUpdate() == 0) throw new SQLException("Workspace not found: " + id);
        }
    }

    public synchronized void opened(String id) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE workspace_catalog SET last_opened_at=?,updated_at=? WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1, now); statement.setString(2, now); statement.setString(3, id);
            if (statement.executeUpdate() == 0) throw new SQLException("Workspace not found: " + id);
        }
    }

    public synchronized void saveDraft(EditorDraft draft) throws SQLException {
        String sql = "INSERT INTO workspace_editor_recovery(workspace_id,editor_id,run_id,title,sql_text,"
                + "sort_order,file_name,file_path,profile_id,dirty,is_active,transaction_state,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(workspace_id,editor_id) DO UPDATE SET "
                + "run_id=excluded.run_id,title=excluded.title,sql_text=excluded.sql_text,sort_order=excluded.sort_order,"
                + "file_name=excluded.file_name,file_path=excluded.file_path,profile_id=excluded.profile_id,"
                + "dirty=excluded.dirty,is_active=excluded.is_active,transaction_state=excluded.transaction_state,"
                + "updated_at=excluded.updated_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindDraft(statement, draft); statement.executeUpdate();
        }
        /*
         * A stable checkpoint represents content that was actually saved to a file. A brand-new
         * temporary tab may be clean only because the user has not typed yet; checkpointing it
         * would incorrectly resurrect that tab after the user chooses to discard crash recovery.
         */
        if (!draft.dirty() && hasSavedFile(draft)) saveCheckpoint(draft);
    }

    public synchronized void saveCheckpoint(EditorDraft draft) throws SQLException {
        String sql = "INSERT INTO workspace_editor_checkpoint(workspace_id,editor_id,title,sql_text,sort_order,"
                + "file_name,file_path,profile_id,is_active,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT(workspace_id,editor_id) DO UPDATE SET title=excluded.title,sql_text=excluded.sql_text,"
                + "sort_order=excluded.sort_order,file_name=excluded.file_name,file_path=excluded.file_path,"
                + "profile_id=excluded.profile_id,is_active=excluded.is_active,updated_at=excluded.updated_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, draft.workspaceId()); statement.setString(2, draft.editorId());
            statement.setString(3, draft.title()); statement.setString(4, draft.sqlText());
            statement.setInt(5, draft.sortOrder()); statement.setString(6, draft.fileName());
            statement.setString(7, draft.filePath()); statement.setString(8, draft.profileId());
            statement.setBoolean(9, draft.active()); statement.setString(10, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public synchronized void removeEditor(String workspaceId, String editorId) throws SQLException {
        deleteEditorRow("workspace_editor_recovery", workspaceId, editorId);
        deleteEditorRow("workspace_editor_checkpoint", workspaceId, editorId);
    }

    public synchronized List<EditorDraft> recoveryDrafts(String workspaceId) throws SQLException {
        return editorRows("workspace_editor_recovery", workspaceId, true);
    }

    public synchronized List<EditorDraft> checkpoints(String workspaceId) throws SQLException {
        return editorRows("workspace_editor_checkpoint", workspaceId, false);
    }

    public synchronized void discardRecovery(String workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM workspace_editor_recovery WHERE workspace_id=?")) {
            statement.setString(1, workspaceId); statement.executeUpdate();
        }
    }

    public synchronized void updateTransactionState(String workspaceId, String editorId,
                                                    String state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE workspace_editor_recovery SET transaction_state=?,updated_at=? "
                        + "WHERE workspace_id=? AND editor_id=?")) {
            statement.setString(1, state == null ? "none" : state);
            statement.setString(2, Instant.now().toString()); statement.setString(3, workspaceId);
            statement.setString(4, editorId); statement.executeUpdate();
        }
    }

    public synchronized String startRun(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO application_run(id,started_at,normal_exit) VALUES(?,?,0)")) {
            statement.setString(1, runId); statement.setString(2, Instant.now().toString());
            statement.executeUpdate();
        }
        return runId;
    }

    public synchronized void finishRun(String runId, boolean normalExit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE application_run SET clean_shutdown_at=?,normal_exit=? WHERE id=?")) {
            statement.setString(1, Instant.now().toString()); statement.setBoolean(2, normalExit);
            statement.setString(3, runId); statement.executeUpdate();
        }
    }

    public synchronized boolean runWasClean(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT normal_exit FROM application_run WHERE id=? AND clean_shutdown_at IS NOT NULL")) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }

    private List<EditorDraft> editorRows(String table, String workspaceId, boolean recovery) throws SQLException {
        String fields = recovery
                ? "workspace_id,editor_id,run_id,title,sql_text,sort_order,file_name,file_path,profile_id,dirty,is_active,transaction_state,updated_at"
                : "workspace_id,editor_id,'' AS run_id,title,sql_text,sort_order,file_name,file_path,profile_id,0 AS dirty,is_active,'none' AS transaction_state,updated_at";
        List<EditorDraft> result = new ArrayList<EditorDraft>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + fields + " FROM " + table + " WHERE workspace_id=? ORDER BY sort_order,editor_id")) {
            statement.setString(1, workspaceId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new EditorDraft(rows.getString("workspace_id"),
                        rows.getString("editor_id"), rows.getString("run_id"), rows.getString("title"),
                        rows.getString("sql_text"), rows.getInt("sort_order"), rows.getString("file_name"),
                        rows.getString("file_path"), rows.getString("profile_id"), rows.getBoolean("dirty"),
                        rows.getBoolean("is_active"), rows.getString("transaction_state"),
                        rows.getString("updated_at")));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static void bindDraft(PreparedStatement statement, EditorDraft draft) throws SQLException {
        statement.setString(1, draft.workspaceId()); statement.setString(2, draft.editorId());
        statement.setString(3, draft.runId()); statement.setString(4, draft.title());
        statement.setString(5, draft.sqlText()); statement.setInt(6, draft.sortOrder());
        statement.setString(7, draft.fileName()); statement.setString(8, draft.filePath());
        statement.setString(9, draft.profileId()); statement.setBoolean(10, draft.dirty());
        statement.setBoolean(11, draft.active()); statement.setString(12, draft.transactionState());
        statement.setString(13, Instant.now().toString());
    }

    private void deleteEditorRow(String table, String workspaceId, String editorId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE workspace_id=? AND editor_id=?")) {
            statement.setString(1, workspaceId); statement.setString(2, editorId); statement.executeUpdate();
        }
    }

    private static WorkspaceRecord workspace(ResultSet rows) throws SQLException {
        return new WorkspaceRecord(rows.getString("id"), rows.getString("local_uuid"),
                rows.getString("machine_fingerprint"), rows.getString("name"), rows.getString("created_at"),
                rows.getString("updated_at"), rows.getString("last_opened_at"), rows.getInt("dirty_count"),
                rows.getInt("transaction_count"));
    }

    private static String normalizedName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Workspace名称不能为空");
        if (name.length() > 80) throw new IllegalArgumentException("Workspace名称不能超过80个字符");
        return name;
    }

    private static boolean hasSavedFile(EditorDraft draft) {
        return (draft.filePath() != null && !draft.filePath().trim().isEmpty())
                || (draft.fileName() != null && !draft.fileName().trim().isEmpty());
    }

    public static final class WorkspaceRecord {
        private final String id, localUuid, machineFingerprint, name, createdAt, updatedAt, lastOpenedAt;
        private final int dirtyCount, transactionCount;
        public WorkspaceRecord(String id, String localUuid, String machineFingerprint, String name,
                               String createdAt, String updatedAt, String lastOpenedAt,
                               int dirtyCount, int transactionCount) {
            this.id=id; this.localUuid=localUuid; this.machineFingerprint=machineFingerprint; this.name=name;
            this.createdAt=createdAt; this.updatedAt=updatedAt; this.lastOpenedAt=lastOpenedAt;
            this.dirtyCount=dirtyCount; this.transactionCount=transactionCount;
        }
        public String id(){return id;} public String localUuid(){return localUuid;}
        public String machineFingerprint(){return machineFingerprint;} public String name(){return name;}
        public String createdAt(){return createdAt;} public String updatedAt(){return updatedAt;}
        public String lastOpenedAt(){return lastOpenedAt;} public int dirtyCount(){return dirtyCount;}
        public int transactionCount(){return transactionCount;}
    }

    public static final class EditorDraft {
        private final String workspaceId, editorId, runId, title, sqlText, fileName, filePath, profileId,
                transactionState, updatedAt;
        private final int sortOrder;
        private final boolean dirty, active;
        public EditorDraft(String workspaceId, String editorId, String runId, String title, String sqlText,
                           int sortOrder, String fileName, String filePath, String profileId, boolean dirty,
                           boolean active, String transactionState, String updatedAt) {
            this.workspaceId=workspaceId; this.editorId=editorId; this.runId=runId; this.title=title;
            this.sqlText=sqlText; this.sortOrder=sortOrder; this.fileName=fileName; this.filePath=filePath;
            this.profileId=profileId; this.dirty=dirty; this.active=active;
            this.transactionState=transactionState == null ? "none" : transactionState; this.updatedAt=updatedAt;
        }
        public String workspaceId(){return workspaceId;} public String editorId(){return editorId;}
        public String runId(){return runId;} public String title(){return title;} public String sqlText(){return sqlText;}
        public int sortOrder(){return sortOrder;} public String fileName(){return fileName;}
        public String filePath(){return filePath;} public String profileId(){return profileId;}
        public boolean dirty(){return dirty;} public boolean active(){return active;}
        public String transactionState(){return transactionState;} public String updatedAt(){return updatedAt;}
    }
}
