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
                "SELECT id, provider_id, name, settings_json, secret_ref, remember_password "
                        + "FROM connection_profile ORDER BY name COLLATE NOCASE");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                try {
                    Map<String, String> settings = objectMapper.readValue(resultSet.getString("settings_json"),
                            new TypeReference<Map<String, String>>() { });
                    profiles.add(new SavedProfile(new ConnectionProfile(
                            UUID.fromString(resultSet.getString("id")),
                            resultSet.getString("provider_id"), resultSet.getString("name"), settings,
                            resultSet.getString("secret_ref")), resultSet.getBoolean("remember_password")));
                } catch (Exception exception) {
                    throw new SQLException("Invalid connection profile: " + resultSet.getString("id"), exception);
                }
            }
        }
        return profiles;
    }

    public synchronized void save(ConnectionProfile profile, boolean rememberPassword) throws SQLException {
        String sql = "INSERT INTO connection_profile(id, provider_id, name, settings_json, secret_ref, "
                + "remember_password, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT(id) DO UPDATE SET provider_id=excluded.provider_id, name=excluded.name, "
                + "settings_json=excluded.settings_json, secret_ref=excluded.secret_ref, "
                + "remember_password=excluded.remember_password, updated_at=excluded.updated_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, profile.id().toString());
            statement.setString(2, profile.providerId());
            statement.setString(3, profile.name());
            try { statement.setString(4, objectMapper.writeValueAsString(profile.settings())); }
            catch (Exception exception) { throw new SQLException("Unable to serialize connection profile", exception); }
            statement.setString(5, profile.secretRef());
            statement.setBoolean(6, rememberPassword);
            statement.setString(7, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    public static final class SavedProfile {
        private final ConnectionProfile profile;
        private final boolean rememberPassword;
        public SavedProfile(ConnectionProfile profile, boolean rememberPassword) {
            this.profile = profile;
            this.rememberPassword = rememberPassword;
        }
        public ConnectionProfile profile() { return profile; }
        public boolean rememberPassword() { return rememberPassword; }
        @Override public String toString() { return profile.name(); }
    }
}
