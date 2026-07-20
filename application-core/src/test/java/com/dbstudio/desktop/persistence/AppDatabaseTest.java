package com.dbstudio.desktop.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void createsVersionFourWorkspaceSchemaAndKeepsRecentFileTableForMigrationCompatibility() throws Exception {
        try (AppDatabase database = new AppDatabase(directory)) {
            try (Statement statement = database.connection().createStatement();
                 ResultSet result = statement.executeQuery("SELECT MAX(version) FROM schema_version")) {
                assertTrue(result.next());
                assertEquals(4, result.getInt(1));
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
            for (String table : new String[] { "workspace_catalog", "workspace_editor_checkpoint",
                    "workspace_editor_recovery", "application_run" }) {
                try (Statement statement = database.connection().createStatement();
                     ResultSet result = statement.executeQuery(
                             "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='" + table + "'")) {
                    assertTrue(result.next()); assertEquals(1, result.getInt(1));
                }
            }
        }
    }

    @Test
    void persistsWorkspaceDraftsAndStableCheckpointsSeparately() throws Exception {
        try (AppDatabase database = new AppDatabase(directory)) {
            WorkspaceRepository workspaces = new WorkspaceRepository(database);
            String workspaceId = UUID.randomUUID().toString();
            String editorId = UUID.randomUUID().toString();
            workspaces.create(workspaceId, UUID.randomUUID().toString(), "machine", "订单开发");
            workspaces.startRun("run-1");
            workspaces.saveDraft(new WorkspaceRepository.EditorDraft(workspaceId, editorId, "run-1",
                    "orders.sql", "select 1", 0, "orders.sql", "orders.sql", null,
                    false, true, "none", null));
            workspaces.saveDraft(new WorkspaceRepository.EditorDraft(workspaceId, editorId, "run-1",
                    "查询 1", "select 2", 0, null, null, null, true, true, "active", null));

            assertEquals("select 1", workspaces.checkpoints(workspaceId).get(0).sqlText());
            assertEquals("select 2", workspaces.recoveryDrafts(workspaceId).get(0).sqlText());
            assertEquals(1, workspaces.find(workspaceId).get().dirtyCount());
            assertEquals(1, workspaces.find(workspaceId).get().transactionCount());

            workspaces.discardRecovery(workspaceId);
            assertTrue(workspaces.recoveryDrafts(workspaceId).isEmpty());
            assertEquals("select 1", workspaces.checkpoints(workspaceId).get(0).sqlText());
        }
    }

    @Test
    void doesNotCreateStableCheckpointForUnsavedTemporaryEditor() throws Exception {
        try (AppDatabase database = new AppDatabase(directory)) {
            WorkspaceRepository workspaces = new WorkspaceRepository(database);
            String workspaceId = UUID.randomUUID().toString();
            workspaces.create(workspaceId, UUID.randomUUID().toString(), "machine", "临时查询");
            workspaces.saveDraft(new WorkspaceRepository.EditorDraft(workspaceId, UUID.randomUUID().toString(),
                    "run-1", "查询 1", "", 0, null, null, null, false, true, "none", null));

            assertTrue(workspaces.checkpoints(workspaceId).isEmpty());
            assertEquals(1, workspaces.recoveryDrafts(workspaceId).size());
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

    @Test
    void movesProfileBetweenEnvironmentsWithoutChangingConnectionRevision() throws Exception {
        try (AppDatabase database = new AppDatabase(directory)) {
            ConnectionCatalogRepository catalog = new ConnectionCatalogRepository(database);
            ConnectionProfileRepository profiles = new ConnectionProfileRepository(database, new ObjectMapper());
            ConnectionCatalogRepository.SystemEntry firstSystem = catalog.createSystem("订单系统");
            ConnectionCatalogRepository.EnvironmentEntry first = catalog.createEnvironment(firstSystem.id(), "DEV");
            ConnectionCatalogRepository.SystemEntry secondSystem = catalog.createSystem("资金系统");
            ConnectionCatalogRepository.EnvironmentEntry second = catalog.createEnvironment(secondSystem.id(), "SIT");
            ConnectionProfile profile = new ConnectionProfile(
                    UUID.randomUUID(), "mysql", "开发库", new HashMap<String, String>(), "secret-ref");
            profiles.save(profile, false, first.id());
            String revision = profiles.find(profile.id()).get().revision();

            profiles.moveToEnvironment(profile.id(), second.id());

            ConnectionProfileRepository.SavedProfile moved = profiles.find(profile.id()).get();
            assertEquals(second.id(), moved.environmentId());
            assertEquals(revision, moved.revision());
            assertThrows(java.sql.SQLException.class,
                    () -> profiles.moveToEnvironment(profile.id(), "missing-environment"));
        }
    }
}
