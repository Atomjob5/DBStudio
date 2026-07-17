package com.dbstudio.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.ConnectionTestResult;
import com.dbstudio.spi.DatabaseSession;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class LocalMySqlRegressionTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "DBSTUDIO_TEST_MYSQL_PASSWORD", matches = ".+")
    void connectsToConfiguredLocalDatabase() throws Exception {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("host", environment("DBSTUDIO_TEST_MYSQL_HOST", "127.0.0.1"));
        settings.put("port", environment("DBSTUDIO_TEST_MYSQL_PORT", "33061"));
        settings.put("database", environment("DBSTUDIO_TEST_MYSQL_DATABASE", "eastwealthcrawler"));
        settings.put("username", environment("DBSTUDIO_TEST_MYSQL_USERNAME", "root"));
        settings.put("timeoutSeconds", "10");
        ConnectionProfile profile = new ConnectionProfile(UUID.randomUUID(), "mysql",
                "local-regression", settings, "local-regression");
        char[] password = System.getenv("DBSTUDIO_TEST_MYSQL_PASSWORD").toCharArray();
        MySqlDatabaseProvider provider = new MySqlDatabaseProvider();
        try {
            ConnectionTestResult result = provider.connections().test(profile, password);
            assertTrue(result.success(), result.message());
            assertFalse(result.serverVersion().trim().isEmpty());
            try (DatabaseSession session = provider.connections().connect(profile, password);
                 Statement statement = session.jdbcConnection().createStatement();
                 ResultSet rows = statement.executeQuery("SELECT DATABASE(), 1")) {
                assertTrue(rows.next());
                assertEquals(settings.get("database"), rows.getString(1));
                assertEquals(1, rows.getInt(2));
                assertFalse(session.jdbcConnection().getAutoCommit());
                assertTrue(provider.metadata().listCatalogs(session).contains(settings.get("database")));
                session.rollback();
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
