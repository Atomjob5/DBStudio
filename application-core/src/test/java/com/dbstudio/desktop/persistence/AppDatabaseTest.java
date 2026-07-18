package com.dbstudio.desktop.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.ConnectionProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppDatabaseTest {
    @TempDir Path directory;

    @Test
    void persistsProfilesWithoutPasswordAndKeepsHistory() throws Exception {
        try (AppDatabase database = new AppDatabase(directory)) {
            ConnectionProfileRepository profiles = new ConnectionProfileRepository(database, new ObjectMapper());
            Map<String, String> values = new HashMap<String, String>();
            values.put("host", "127.0.0.1"); values.put("username", "root");
            ConnectionProfile profile = new ConnectionProfile(
                    UUID.randomUUID(), "mysql", "local", values, "secret-ref");
            profiles.save(profile, true);

            ConnectionProfileRepository.SavedProfile saved = profiles.findAll().get(0);
            assertEquals(profile.id(), saved.profile().id());
            assertEquals(profile.settings(), saved.profile().settings());
            assertTrue(saved.rememberPassword());
            assertFalse(saved.profile().settings().containsKey("password"));
            try (Statement statement = database.connection().createStatement();
                 ResultSet result = statement.executeQuery("SELECT settings_json FROM connection_profile")) {
                assertTrue(result.next());
                assertFalse(result.getString(1).toLowerCase().contains("password"));
            }
        }
    }

    @Test
    void createsVersionThreeCatalogSchemaAndKeepsRecentFileTableForMigrationCompatibility() throws Exception {
        try (AppDatabase database = new AppDatabase(directory)) {
            try (Statement statement = database.connection().createStatement();
                 ResultSet result = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertTrue(result.next());
                assertEquals(3, result.getInt(1));
            }
            try (Statement statement = database.connection().createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='recent_file'")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
            try (Statement statement = database.connection().createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT s.name, e.name FROM connection_system s "
                                 + "JOIN connection_environment e ON e.system_id=s.id")) {
                assertTrue(result.next());
                assertEquals("未分类系统", result.getString(1));
                assertEquals("默认环境", result.getString(2));
            }
        }
    }

    @Test
    void managesThreeLevelConnectionCatalogAndHidesSoftDeletedBranches() throws Exception {
        try (AppDatabase database = new AppDatabase(directory)) {
            ConnectionCatalogRepository catalog = new ConnectionCatalogRepository(database);
            ConnectionProfileRepository profiles = new ConnectionProfileRepository(database, new ObjectMapper());
            ConnectionCatalogRepository.SystemEntry system = catalog.createSystem("订单系统");
            ConnectionCatalogRepository.EnvironmentEntry environment =
                    catalog.createEnvironment(system.id(), "DEV");
            Map<String, String> values = new HashMap<String, String>();
            values.put("host", "127.0.0.1");
            ConnectionProfile profile = new ConnectionProfile(
                    UUID.randomUUID(), "mysql", "开发库", values, "secret-ref");
            profiles.save(profile, false, environment.id());

            assertEquals(environment.id(), profiles.find(profile.id()).get().environmentId());
            assertTrue(catalog.findEnvironment(environment.id()).isPresent());

            catalog.deleteSystem(system.id());
            assertFalse(catalog.findEnvironment(environment.id()).isPresent());
            assertFalse(profiles.find(profile.id()).isPresent());
        }
    }
}
