package com.dbstudio.desktop.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persists the user-defined System -> Environment connection catalog. */
public final class ConnectionCatalogRepository {
    private final Connection connection;

    public ConnectionCatalogRepository(AppDatabase database) {
        this.connection = database.connection();
    }

    public synchronized List<SystemEntry> systems() throws SQLException {
        List<SystemEntry> result = new ArrayList<SystemEntry>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, updated_at FROM connection_system WHERE deleted_at IS NULL "
                        + "ORDER BY name COLLATE NOCASE");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new SystemEntry(rows.getString(1), rows.getString(2), rows.getString(3)));
        }
        return result;
    }

    public synchronized List<EnvironmentEntry> environments() throws SQLException {
        List<EnvironmentEntry> result = new ArrayList<EnvironmentEntry>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT e.id, e.system_id, e.name, e.updated_at FROM connection_environment e "
                        + "JOIN connection_system s ON s.id=e.system_id "
                        + "WHERE e.deleted_at IS NULL AND s.deleted_at IS NULL "
                        + "ORDER BY e.name COLLATE NOCASE");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new EnvironmentEntry(
                    rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
        }
        return result;
    }

    public synchronized Optional<SystemEntry> findSystem(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, name, updated_at FROM connection_system WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new SystemEntry(rows.getString(1), rows.getString(2), rows.getString(3)))
                        : Optional.<SystemEntry>empty();
            }
        }
    }

    public synchronized Optional<EnvironmentEntry> findEnvironment(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT e.id, e.system_id, e.name, e.updated_at FROM connection_environment e "
                        + "JOIN connection_system s ON s.id=e.system_id "
                        + "WHERE e.id=? AND e.deleted_at IS NULL AND s.deleted_at IS NULL")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(new EnvironmentEntry(
                        rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)))
                        : Optional.<EnvironmentEntry>empty();
            }
        }
    }

    public synchronized SystemEntry createSystem(String name) throws SQLException {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO connection_system(id, name, updated_at) VALUES (?, ?, ?)")) {
            statement.setString(1, id); statement.setString(2, name); statement.setString(3, now);
            statement.executeUpdate();
        }
        return new SystemEntry(id, name, now);
    }

    public synchronized SystemEntry renameSystem(String id, String name) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE connection_system SET name=?, updated_at=? WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1, name); statement.setString(2, now); statement.setString(3, id);
            if (statement.executeUpdate() == 0) throw new SQLException("Connection system not found: " + id);
        }
        return new SystemEntry(id, name, now);
    }

    public synchronized EnvironmentEntry createEnvironment(String systemId, String name) throws SQLException {
        if (!findSystem(systemId).isPresent()) throw new SQLException("Connection system not found: " + systemId);
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO connection_environment(id, system_id, name, updated_at) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, id); statement.setString(2, systemId);
            statement.setString(3, name); statement.setString(4, now); statement.executeUpdate();
        }
        return new EnvironmentEntry(id, systemId, name, now);
    }

    public synchronized EnvironmentEntry renameEnvironment(String id, String name) throws SQLException {
        EnvironmentEntry current = findEnvironment(id).orElseThrow(
                () -> new SQLException("Connection environment not found: " + id));
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE connection_environment SET name=?, updated_at=? WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1, name); statement.setString(2, now); statement.setString(3, id);
            statement.executeUpdate();
        }
        return new EnvironmentEntry(id, current.systemId(), name, now);
    }

    public synchronized void deleteSystem(String id) throws SQLException { softDelete("connection_system", id); }
    public synchronized void deleteEnvironment(String id) throws SQLException { softDelete("connection_environment", id); }

    public synchronized String defaultEnvironmentId() throws SQLException {
        List<EnvironmentEntry> values = environments();
        if (!values.isEmpty()) return values.get(0).id();
        SystemEntry system = createSystem("未分类系统");
        return createEnvironment(system.id(), "默认环境").id();
    }

    private void softDelete(String table, String id) throws SQLException {
        String sql = "UPDATE " + table + " SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL";
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, now); statement.setString(2, now); statement.setString(3, id);
            if (statement.executeUpdate() == 0) throw new SQLException("Connection catalog item not found: " + id);
        }
    }

    public static final class SystemEntry {
        private final String id; private final String name; private final String revision;
        public SystemEntry(String id, String name, String revision) {
            this.id = id; this.name = name; this.revision = revision;
        }
        public String id() { return id; }
        public String name() { return name; }
        public String revision() { return revision; }
    }

    public static final class EnvironmentEntry {
        private final String id; private final String systemId; private final String name; private final String revision;
        public EnvironmentEntry(String id, String systemId, String name, String revision) {
            this.id = id; this.systemId = systemId; this.name = name; this.revision = revision;
        }
        public String id() { return id; }
        public String systemId() { return systemId; }
        public String name() { return name; }
        public String revision() { return revision; }
    }
}
