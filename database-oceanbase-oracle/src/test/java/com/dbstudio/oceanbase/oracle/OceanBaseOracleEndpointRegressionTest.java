package com.dbstudio.oceanbase.oracle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseNamespace;
import com.dbstudio.spi.DatabaseSession;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

final class OceanBaseOracleEndpointRegressionTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DBSTUDIO_TEST_OCEANBASE_ORACLE_PASSWORD", matches = ".+")
    void validatesConfiguredOracleModeEndpoint() throws Exception {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("host", environment("DBSTUDIO_TEST_OCEANBASE_ORACLE_HOST", "127.0.0.1"));
        settings.put("port", environment("DBSTUDIO_TEST_OCEANBASE_ORACLE_PORT", "2881"));
        settings.put("database", environment("DBSTUDIO_TEST_OCEANBASE_ORACLE_DATABASE", "ORACLE"));
        settings.put("username", environment("DBSTUDIO_TEST_OCEANBASE_ORACLE_USERNAME", "app@tenant"));
        settings.put("schema", environment("DBSTUDIO_TEST_OCEANBASE_ORACLE_SCHEMA", "APP"));
        settings.put("timeoutSeconds", "20");
        ConnectionProfile profile = new ConnectionProfile(UUID.randomUUID(), "oceanbase-oracle",
                "oceanbase-oracle-regression", settings, "oceanbase-oracle-regression");
        char[] password = System.getenv("DBSTUDIO_TEST_OCEANBASE_ORACLE_PASSWORD").toCharArray();
        OceanBaseOracleDatabaseProvider provider = new OceanBaseOracleDatabaseProvider();
        try {
            assertTrue(provider.connections().test(profile, password).success());
            try (DatabaseSession session = provider.connections().connect(profile, password);
                 Statement statement = session.jdbcConnection().createStatement();
                 ResultSet rows = statement.executeQuery("SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL")) {
                assertTrue(rows.next());
                List<DatabaseNamespace> namespaces = provider.metadata().listNamespaces(session);
                assertFalse(namespaces.isEmpty());
                assertTrue(namespaces.stream().anyMatch(DatabaseNamespace::current));
                provider.connections().resetSession(session, profile);
                assertFalse(session.jdbcConnection().getAutoCommit());
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String environment(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
