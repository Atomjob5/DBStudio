package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.desktop.persistence.AppDatabase;
import com.dbstudio.desktop.persistence.ConnectionCatalogRepository;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.security.SecretStore;
import com.dbstudio.desktop.security.SecretStoreException;
import com.dbstudio.spi.ConnectionProfile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionProfileCloneServiceTest {
    @TempDir Path directory;
    private AppDatabase database;
    private ConnectionProfileRepository profiles;
    private ConnectionCatalogRepository catalog;
    private TrackingSecretStore secrets;
    private ConnectionProfileCloneService service;
    private String environmentId;

    @BeforeEach
    void setUp() throws Exception {
        database = new AppDatabase(directory);
        profiles = new ConnectionProfileRepository(database, new ObjectMapper());
        catalog = new ConnectionCatalogRepository(database);
        secrets = new TrackingSecretStore();
        service = new ConnectionProfileCloneService(database, profiles, secrets);
        ConnectionCatalogRepository.SystemEntry system = catalog.createSystem("订单系统");
        environmentId = catalog.createEnvironment(system.id(), "DEV").id();
    }

    @AfterEach
    void tearDown() throws Exception {
        secrets.close();
        database.close();
    }

    @Test
    void clonesConfigurationIntoTheSameEnvironmentAndGeneratesTheNextAvailableName() throws Exception {
        SavedProfile source = save("订单库", false, environmentId);
        save("订单库 - 副本", false, environmentId);
        save(" 订单库 - 副本 2 ", false, environmentId);
        ConnectionCatalogRepository.SystemEntry otherSystem = catalog.createSystem("报表系统");
        String otherEnvironment = catalog.createEnvironment(otherSystem.id(), "DEV").id();
        save("订单库 - 副本 3", false, otherEnvironment);

        ConnectionProfileCloneService.CloneResult result =
                service.cloneProfile(source.profile().id());

        SavedProfile cloned = result.profile();
        assertEquals("订单库 - 副本 3", cloned.profile().name());
        assertEquals(environmentId, cloned.environmentId());
        assertEquals(source.profile().providerId(), cloned.profile().providerId());
        assertEquals(source.profile().settings(), cloned.profile().settings());
        assertNotEquals(source.profile().id(), cloned.profile().id());
        assertNotEquals(source.profile().secretRef(), cloned.profile().secretRef());
        assertFalse(cloned.rememberPassword());
        assertEquals(ConnectionProfileCloneService.PASSWORD_NOT_REMEMBERED,
                result.passwordStatus());
        assertEquals("订单库", profiles.find(source.profile().id()).get().profile().name());
    }

    @Test
    void cloningAnExistingCopyContinuesTheRootSequenceIgnoringCaseAndWhitespace() throws Exception {
        save("订单库 - 副本", false, environmentId);
        save(" 订单库 - 副本 2 ", false, environmentId);
        SavedProfile source = save("订单库 - 副本 3", false, environmentId);

        ConnectionProfileCloneService.CloneResult result =
                service.cloneProfile(source.profile().id());

        assertEquals("订单库 - 副本 4", result.profile().profile().name());
    }

    @Test
    void copiesRememberedPasswordToAnIndependentSecretReference() throws Exception {
        SavedProfile source = save("订单库", true, environmentId);
        secrets.save(source.profile().secretRef(), "source-secret".toCharArray());

        ConnectionProfileCloneService.CloneResult result =
                service.cloneProfile(source.profile().id());

        SavedProfile cloned = result.profile();
        assertTrue(cloned.rememberPassword());
        assertEquals(ConnectionProfileCloneService.PASSWORD_COPIED, result.passwordStatus());
        assertEquals("source-secret", secrets.text(cloned.profile().secretRef()));
        assertEquals("source-secret", secrets.text(source.profile().secretRef()));
    }

    @Test
    void createsAnUnrememberedCloneWhenTheSourceSecretIsMissingOrUnreadable() throws Exception {
        SavedProfile missing = save("缺失密码", true, environmentId);
        ConnectionProfileCloneService.CloneResult missingResult =
                service.cloneProfile(missing.profile().id());
        assertFalse(missingResult.profile().rememberPassword());
        assertEquals(ConnectionProfileCloneService.PASSWORD_UNAVAILABLE,
                missingResult.passwordStatus());

        SavedProfile unreadable = save("密钥库异常", true, environmentId);
        secrets.failLoads = true;
        ConnectionProfileCloneService.CloneResult unreadableResult =
                service.cloneProfile(unreadable.profile().id());
        assertFalse(unreadableResult.profile().rememberPassword());
        assertEquals(ConnectionProfileCloneService.PASSWORD_UNAVAILABLE,
                unreadableResult.passwordStatus());
    }

    @Test
    void createsAnUnrememberedCloneWhenCopyingTheSecretFailsAndCleanupSucceeds() throws Exception {
        SavedProfile source = save("订单库", true, environmentId);
        secrets.save(source.profile().secretRef(), "source-secret".toCharArray());
        secrets.failTargetSaves = true;

        ConnectionProfileCloneService.CloneResult result =
                service.cloneProfile(source.profile().id());

        assertFalse(result.profile().rememberPassword());
        assertEquals(ConnectionProfileCloneService.PASSWORD_UNAVAILABLE,
                result.passwordStatus());
        assertEquals(1, secrets.size());
    }

    @Test
    void rollsBackTheProfileAndDeletesTheCopiedSecretWhenSqliteSaveFails() throws Exception {
        SavedProfile source = save("订单库", true, environmentId);
        secrets.save(source.profile().secretRef(), "source-secret".toCharArray());
        try (Statement statement = database.connection().createStatement()) {
            statement.execute("CREATE TRIGGER fail_profile_clone BEFORE INSERT ON connection_profile "
                    + "WHEN NEW.id <> '" + source.profile().id() + "' "
                    + "BEGIN SELECT RAISE(ABORT, 'clone failed'); END");
        }

        ApiException error = assertThrows(ApiException.class,
                () -> service.cloneProfile(source.profile().id()));

        assertEquals("CONNECTION_CLONE_FAILED", error.getCode());
        assertEquals(1, profiles.findAll().size());
        assertEquals(1, secrets.size());
        assertEquals("source-secret", secrets.text(source.profile().secretRef()));
    }

    @Test
    void rejectsADeletedOrUnknownSource() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.cloneProfile(UUID.randomUUID()));
        assertEquals("PROFILE_NOT_FOUND", error.getCode());
    }

    private SavedProfile save(String name, boolean rememberPassword, String targetEnvironment)
            throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("host", "127.0.0.1");
        settings.put("port", "3306");
        settings.put("database", "orders");
        ConnectionProfile profile = new ConnectionProfile(
                id, "mysql", name, settings, "dbstudio/" + id);
        profiles.save(profile, rememberPassword, targetEnvironment);
        return profiles.find(id).get();
    }

    private static final class TrackingSecretStore implements SecretStore, AutoCloseable {
        private final Map<String, char[]> values = new LinkedHashMap<String, char[]>();
        private boolean failLoads;
        private boolean failTargetSaves;

        @Override
        public void save(String reference, char[] secret) throws SecretStoreException {
            if (failTargetSaves) throw new SecretStoreException("模拟密钥库写入失败");
            char[] previous = values.put(reference, Arrays.copyOf(secret, secret.length));
            clear(previous);
        }

        @Override
        public Optional<char[]> load(String reference) throws SecretStoreException {
            if (failLoads) throw new SecretStoreException("模拟密钥库读取失败");
            char[] value = values.get(reference);
            return value == null ? Optional.<char[]>empty()
                    : Optional.of(Arrays.copyOf(value, value.length));
        }

        @Override
        public void delete(String reference) {
            clear(values.remove(reference));
        }

        private String text(String reference) {
            char[] value = values.get(reference);
            return value == null ? "" : new String(value);
        }

        private int size() {
            return values.size();
        }

        @Override
        public void close() {
            for (char[] value : values.values()) clear(value);
            values.clear();
        }

        private static void clear(char[] value) {
            if (value != null) Arrays.fill(value, '\0');
        }
    }
}
