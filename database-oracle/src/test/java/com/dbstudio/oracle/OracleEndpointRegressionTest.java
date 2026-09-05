package com.dbstudio.oracle;

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

final class OracleEndpointRegressionTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DBSTUDIO_TEST_ORACLE_PASSWORD", matches = ".+")
    void validatesConfiguredOracle19cOr21cEndpoint() throws Exception {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("host", environment("DBSTUDIO_TEST_ORACLE_HOST", "127.0.0.1"));
        settings.put("port", environment("DBSTUDIO_TEST_ORACLE_PORT", "1521"));
        settings.put("connectionMode", environment("DBSTUDIO_TEST_ORACLE_MODE", "service"));
        settings.put("service", environment("DBSTUDIO_TEST_ORACLE_SERVICE", "ORCLPDB1"));
        settings.put("username", environment("DBSTUDIO_TEST_ORACLE_USERNAME", "system"));
        settings.put("schema", environment("DBSTUDIO_TEST_ORACLE_SCHEMA", settings.get("username")));
        settings.put("timeoutSeconds", "20");
        ConnectionProfile profile = new ConnectionProfile(UUID.randomUUID(), "oracle", "oracle-regression",
                settings, "oracle-regression");
        char[] password = System.getenv("DBSTUDIO_TEST_ORACLE_PASSWORD").toCharArray();
        OracleDatabaseProvider provider = new OracleDatabaseProvider();
        try {
            assertTrue(provider.connections().test(profile, password).success());
            try (DatabaseSession session = provider.connections().connect(profile, password);
                 Statement statement = session.jdbcConnection().createStatement();
                 ResultSet rows = statement.executeQuery("SELECT 1 FROM DUAL")) {
                assertTrue(rows.next());
                assertTrue(rows.getInt(1) == 1);
                List<DatabaseNamespace> namespaces = provider.metadata().listNamespaces(session);
                assertFalse(namespaces.isEmpty());
                assertTrue(namespaces.stream().anyMatch(DatabaseNamespace::current));
                provider.connections().resetSession(session, profile);
                com.dbstudio.spi.ExecutionPlan plan = provider.executionPlans().explain(session, "SELECT 1 FROM DUAL",
                        new com.dbstudio.spi.ExecutionPlanAdapter.Control() {
                            public void active(Statement active) { }
                            public void checkCancelled() { }
                            public void cleanup() { }
                        });
                assertFalse(plan.getRawText().isEmpty());
                assertFalse(plan.getNodes().isEmpty(), plan.getWarning());
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
