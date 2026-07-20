package com.dbstudio.desktop.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

public final class AppDatabase implements AutoCloseable {
    private static final int SCHEMA_VERSION = 4;
    private final Connection connection;

    public AppDatabase(Path dataDirectory) throws SQLException, IOException {
        Files.createDirectories(dataDirectory);
        connection = DriverManager.getConnection("jdbc:sqlite:" + dataDirectory.resolve("dbstudio.db"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        migrate();
    }

    public Connection connection() { return connection; }

    private void migrate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)");
        }
        int version = currentVersion();
        if (version == 0) { migrateToV1(); recordVersion(1); version = 1; }
        if (version == 1) { migrateToV2(); recordVersion(2); version = 2; }
        if (version == 2) { migrateToV3(); recordVersion(3); version = 3; }
        if (version == 3) { migrateToV4(); recordVersion(4); version = 4; }
        if (version > SCHEMA_VERSION) {
            throw new SQLException("Local database schema is newer than this application: " + version);
        }
    }

    private int currentVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private void migrateToV1() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE connection_profile ("
                    + "id TEXT PRIMARY KEY, provider_id TEXT NOT NULL, name TEXT NOT NULL, "
                    + "settings_json TEXT NOT NULL, secret_ref TEXT NOT NULL, "
                    + "remember_password INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL)");
            statement.execute("CREATE TABLE query_history ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id TEXT, catalog_name TEXT, "
                    + "sql_text TEXT NOT NULL, executed_at TEXT NOT NULL, duration_ms INTEGER NOT NULL, "
                    + "status TEXT NOT NULL, row_count INTEGER NOT NULL DEFAULT 0, error_message TEXT)");
            statement.execute("CREATE INDEX idx_query_history_executed_at ON query_history(executed_at DESC)");
            statement.execute("CREATE TABLE app_setting (setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void migrateToV2() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS recent_file (path TEXT PRIMARY KEY, opened_at TEXT NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_recent_file_opened_at ON recent_file(opened_at DESC)");
        }
    }

    private void migrateToV3() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        String systemId = UUID.randomUUID().toString();
        String environmentId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE connection_system ("
                    + "id TEXT PRIMARY KEY, name TEXT NOT NULL, updated_at TEXT NOT NULL, deleted_at TEXT)");
            statement.execute("CREATE TABLE connection_environment ("
                    + "id TEXT PRIMARY KEY, system_id TEXT NOT NULL, name TEXT NOT NULL, "
                    + "updated_at TEXT NOT NULL, deleted_at TEXT, "
                    + "FOREIGN KEY(system_id) REFERENCES connection_system(id))");
            statement.execute("ALTER TABLE connection_profile ADD COLUMN environment_id TEXT");
            statement.execute("ALTER TABLE connection_profile ADD COLUMN deleted_at TEXT");
            statement.execute("CREATE UNIQUE INDEX idx_connection_system_active_name "
                    + "ON connection_system(name COLLATE NOCASE) WHERE deleted_at IS NULL");
            statement.execute("CREATE UNIQUE INDEX idx_connection_environment_active_name "
                    + "ON connection_environment(system_id, name COLLATE NOCASE) WHERE deleted_at IS NULL");
            statement.execute("CREATE INDEX idx_connection_profile_environment "
                    + "ON connection_profile(environment_id)");
            try (java.sql.PreparedStatement insertSystem = connection.prepareStatement(
                    "INSERT INTO connection_system(id, name, updated_at) VALUES (?, ?, ?)");
                 java.sql.PreparedStatement insertEnvironment = connection.prepareStatement(
                         "INSERT INTO connection_environment(id, system_id, name, updated_at) VALUES (?, ?, ?, ?)");
                 java.sql.PreparedStatement assignProfiles = connection.prepareStatement(
                         "UPDATE connection_profile SET environment_id=? WHERE environment_id IS NULL")) {
                insertSystem.setString(1, systemId);
                insertSystem.setString(2, "未分类系统");
                insertSystem.setString(3, now);
                insertSystem.executeUpdate();
                insertEnvironment.setString(1, environmentId);
                insertEnvironment.setString(2, systemId);
                insertEnvironment.setString(3, "默认环境");
                insertEnvironment.setString(4, now);
                insertEnvironment.executeUpdate();
                assignProfiles.setString(1, environmentId);
                assignProfiles.executeUpdate();
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void migrateToV4() throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE workspace_catalog ("
                    + "id TEXT PRIMARY KEY, local_uuid TEXT NOT NULL, machine_fingerprint TEXT NOT NULL, "
                    + "name TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, "
                    + "last_opened_at TEXT, deleted_at TEXT)");
            statement.execute("CREATE UNIQUE INDEX idx_workspace_active_name "
                    + "ON workspace_catalog(name COLLATE NOCASE) WHERE deleted_at IS NULL");
            statement.execute("CREATE TABLE workspace_editor_checkpoint ("
                    + "workspace_id TEXT NOT NULL, editor_id TEXT NOT NULL, title TEXT NOT NULL, "
                    + "sql_text TEXT NOT NULL, sort_order INTEGER NOT NULL, file_name TEXT, file_path TEXT, "
                    + "profile_id TEXT, is_active INTEGER NOT NULL DEFAULT 0, updated_at TEXT NOT NULL, "
                    + "PRIMARY KEY(workspace_id, editor_id), "
                    + "FOREIGN KEY(workspace_id) REFERENCES workspace_catalog(id))");
            statement.execute("CREATE TABLE workspace_editor_recovery ("
                    + "workspace_id TEXT NOT NULL, editor_id TEXT NOT NULL, run_id TEXT NOT NULL, "
                    + "title TEXT NOT NULL, sql_text TEXT NOT NULL, sort_order INTEGER NOT NULL, "
                    + "file_name TEXT, file_path TEXT, profile_id TEXT, dirty INTEGER NOT NULL DEFAULT 0, "
                    + "is_active INTEGER NOT NULL DEFAULT 0, transaction_state TEXT NOT NULL DEFAULT 'none', "
                    + "updated_at TEXT NOT NULL, PRIMARY KEY(workspace_id, editor_id), "
                    + "FOREIGN KEY(workspace_id) REFERENCES workspace_catalog(id))");
            statement.execute("CREATE INDEX idx_workspace_recovery_run "
                    + "ON workspace_editor_recovery(run_id, workspace_id)");
            statement.execute("CREATE TABLE application_run ("
                    + "id TEXT PRIMARY KEY, started_at TEXT NOT NULL, clean_shutdown_at TEXT, "
                    + "normal_exit INTEGER NOT NULL DEFAULT 0)");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void recordVersion(int version) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version(version) VALUES (?)")) {
            statement.setInt(1, version);
            statement.executeUpdate();
        }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
