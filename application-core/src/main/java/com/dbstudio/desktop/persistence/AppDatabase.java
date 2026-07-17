package com.dbstudio.desktop.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class AppDatabase implements AutoCloseable {
    private static final int SCHEMA_VERSION = 2;
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

    private void recordVersion(int version) throws SQLException {
        try (java.sql.PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schema_version(version) VALUES (?)")) {
            statement.setInt(1, version);
            statement.executeUpdate();
        }
    }

    @Override public void close() throws SQLException { connection.close(); }
}
