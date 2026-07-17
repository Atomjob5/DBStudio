package com.dbstudio.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseObjectType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL_80 = mysql("mysql:8.0.46");

    @Container
    static final MySQLContainer<?> MYSQL_84 = mysql("mysql:8.4.9");

    @Test
    void supportsConnectionMetadataDmlAndRollbackOnBothTargets() throws Exception {
        verify(MYSQL_80);
        verify(MYSQL_84);
    }

    private void verify(MySQLContainer<?> mysql) throws Exception {
        MySqlDatabaseProvider provider = new MySqlDatabaseProvider();
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("host", mysql.getHost());
        settings.put("port", Integer.toString(mysql.getMappedPort(3306)));
        settings.put("database", mysql.getDatabaseName());
        settings.put("username", mysql.getUsername());
        settings.put("timeoutSeconds", "20");
        ConnectionProfile profile = new ConnectionProfile(
                UUID.randomUUID(), "mysql", mysql.getDockerImageName(),
                settings, "integration");
        char[] password = mysql.getPassword().toCharArray();
        assertTrue(provider.connections().test(profile, password).success());
        try (com.dbstudio.spi.DatabaseSession session = provider.connections().connect(profile, password)) {
            try (java.sql.Statement statement = session.jdbcConnection().createStatement()) {
                statement.execute("CREATE TABLE contract_test(id INT PRIMARY KEY, name VARCHAR(100))");
                statement.executeUpdate("INSERT INTO contract_test VALUES (1, 'first')");
            }
            assertTrue(provider.metadata().listObjects(
                    session, mysql.getDatabaseName(), DatabaseObjectType.TABLE).stream()
                    .anyMatch(object -> object.name().equals("contract_test")));
            assertEquals(2, provider.metadata().listColumns(
                    session, mysql.getDatabaseName(), "", "contract_test").size());
            session.rollback();
            try (java.sql.Statement statement = session.jdbcConnection().createStatement();
                 java.sql.ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM contract_test")) {
                result.next();
                assertEquals(0, result.getInt(1));
            }
            assertFalse(session.jdbcConnection().getAutoCommit());
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    private static MySQLContainer<?> mysql(String image) {
        return new MySQLContainer<>(image)
                .withDatabaseName("dbstudio")
                .withUsername("dbstudio")
                .withPassword("dbstudio-test-password");
    }
}
