package com.dbstudio.desktop.persistence;

import com.dbstudio.spi.ConnectionProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class ConnectionProfileRepository {
    private final Connection connection;
    private final ObjectMapper objectMapper;

    public ConnectionProfileRepository(AppDatabase database, ObjectMapper objectMapper) {
        this.connection = database.connection();
        this.objectMapper = objectMapper;
    }

    public synchronized List<SavedProfile> findAll() throws SQLException {
        List<SavedProfile> profiles = new ArrayList<SavedProfile>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.id, p.provider_id, p.name, p.settings_json, p.secret_ref, p.remember_password, "
                        + "p.environment_id, p.updated_at FROM connection_profile p "
                        + "JOIN connection_environment e ON e.id=p.environment_id "
                        + "JOIN connection_system s ON s.id=e.system_id "
                        + "WHERE p.deleted_at IS NULL AND e.deleted_at IS NULL AND s.deleted_at IS NULL "
                        + "ORDER BY p.name COLLATE NOCASE");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                try {
                    Map<String, String> settings = objectMapper.readValue(resultSet.getString("settings_json"),
                            new TypeReference<Map<String, String>>() { });
                    profiles.add(new SavedProfile(new ConnectionProfile(
                            UUID.fromString(resultSet.getString("id")),
                            resultSet.getString("provider_id"), resultSet.getString("name"), settings,
                            resultSet.getString("secret_ref")), resultSet.getBoolean("remember_password"),
                            resultSet.getString("environment_id"), resultSet.getString("updated_at")));
                } catch (Exception exception) {
                    throw new SQLException("Invalid connection profile: " + resultSet.getString("id"), exception);
                }
            }
        }
        return profiles;
    }

    public synchronized Optional<SavedProfile> find(UUID id) throws SQLException {
        for (SavedProfile profile : findAll()) if (profile.profile().id().equals(id)) return Optional.of(profile);
        return Optional.empty();
    }

    public synchronized void save(ConnectionProfile profile, boolean rememberPassword,
                                  String environmentId) throws SQLException {
        String sql = "INSERT INTO connection_profile(id, provider_id, name, settings_json, secret_ref, "
                + "remember_password, updated_at, environment_id, deleted_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL) "
                + "ON CONFLICT(id) DO UPDATE SET provider_id=excluded.provider_id, name=excluded.name, "
                + "settings_json=excluded.settings_json, secret_ref=excluded.secret_ref, "
                + "remember_password=excluded.remember_password, updated_at=excluded.updated_at, "
                + "environment_id=excluded.environment_id, deleted_at=NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile.id().toString());
            statement.setString(2, profile.providerId());
            statement.setString(3, profile.name());
            try { statement.setString(4, objectMapper.writeValueAsString(profile.settings())); }
            catch (Exception exception) { throw new SQLException("Unable to serialize connection profile", exception); }
            statement.setString(5, profile.secretRef());
            statement.setBoolean(6, rememberPassword);
            statement.setString(7, Instant.now().toString());
            statement.setString(8, environmentId);
            statement.executeUpdate();
        }
    }

    /** Compatibility helper used by callers that do not yet provide a catalog location. */
    public synchronized void save(ConnectionProfile profile, boolean rememberPassword) throws SQLException {
        String environmentId = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT e.id FROM connection_environment e JOIN connection_system s ON s.id=e.system_id "
                        + "WHERE e.deleted_at IS NULL AND s.deleted_at IS NULL ORDER BY e.updated_at LIMIT 1");
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) environmentId = rows.getString(1);
        }
        if (environmentId == null) throw new SQLException("No active connection environment");
        save(profile, rememberPassword, environmentId);
    }

    public synchronized void softDelete(UUID id) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE connection_profile SET deleted_at=?, updated_at=? WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1, now); statement.setString(2, now); statement.setString(3, id.toString());
            if (statement.executeUpdate() == 0) throw new SQLException("Connection profile not found: " + id);
        }
    }

    /** Moves a profile in the catalog without changing its connection revision. */
    public synchronized void moveToEnvironment(UUID id, String environmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM connection_environment e JOIN connection_system s ON s.id=e.system_id "
                        + "WHERE e.id=? AND e.deleted_at IS NULL AND s.deleted_at IS NULL")) {
            statement.setString(1, environmentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Connection environment not found: " + environmentId);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE connection_profile SET environment_id=? WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1, environmentId);
            statement.setString(2, id.toString());
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Connection profile not found: " + id);
            }
        }
    }

    public static final class SavedProfile {
        private final ConnectionProfile profile;
        private final boolean rememberPassword;
        private final String environmentId;
        private final String revision;
        public SavedProfile(ConnectionProfile profile, boolean rememberPassword,
                            String environmentId, String revision) {
            this.profile = profile;
            this.rememberPassword = rememberPassword;
            this.environmentId = environmentId;
            this.revision = revision;
        }
        public SavedProfile(ConnectionProfile profile, boolean rememberPassword) {
            this(profile, rememberPassword, "", "");
        }
        public ConnectionProfile profile() { return profile; }
        public boolean rememberPassword() { return rememberPassword; }
        public String environmentId() { return environmentId; }
        public String revision() { return revision; }
        @Override public String toString() { return profile.name(); }
    }
}
