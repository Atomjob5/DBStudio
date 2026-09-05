package com.dbstudio.mysql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.sql.SQLException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
                StringBuilder longDdl = new StringBuilder("CREATE TABLE inspector_long_ddl(id INT PRIMARY KEY");
                for (int index = 0; index < 400; index++) {
                    longDdl.append(", inspector_column_").append(index).append("_with_long_name INT DEFAULT ")
                            .append(index);
                }
                longDdl.append(") PARTITION BY RANGE(id) (PARTITION p0 VALUES LESS THAN (10),")
                        .append(" PARTITION pmax VALUES LESS THAN MAXVALUE)");
                statement.execute(longDdl.toString());
                // MySQL DDL commits implicitly, so create all fixtures before exercising rollback.
                statement.executeUpdate("INSERT INTO contract_test VALUES (1, 'first')");
            }
            com.dbstudio.spi.ExecutionPlanAdapter.Control control = new com.dbstudio.spi.ExecutionPlanAdapter.Control() {
                public void active(java.sql.Statement statement) { }
                public void checkCancelled() { }
                public void cleanup() { }
            };
            for (String sql : new String[] { "SELECT * FROM contract_test", "SELECT count(*) FROM contract_test GROUP BY name",
                    "WITH c AS (SELECT * FROM contract_test) SELECT * FROM c",
                    "SELECT a.id FROM contract_test a JOIN contract_test b ON a.id=b.id",
                    "SELECT * FROM contract_test WHERE id IN (SELECT id FROM contract_test)",
                    "UPDATE contract_test SET name='changed' WHERE id=1", "DELETE FROM contract_test WHERE id=1",
                    "INSERT INTO contract_test VALUES(2, 'not inserted')" }) {
                com.dbstudio.spi.ExecutionPlan plan = provider.executionPlans().explain(session, sql, control);
                assertFalse(plan.getRawText().isEmpty()); assertFalse(plan.getNodes().isEmpty(), plan.getWarning());
            }
            try (java.sql.Statement statement = session.jdbcConnection().createStatement();
                 java.sql.ResultSet rows = statement.executeQuery("SELECT id,name FROM contract_test")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals("first", rows.getString(2));
                assertFalse(rows.next());
            }
            assertTrue(provider.metadata().listObjects(
                    session, mysql.getDatabaseName(), DatabaseObjectType.TABLE).stream()
                    .anyMatch(object -> object.name().equals("contract_test")));
            assertEquals(2, provider.metadata().listColumns(
                    session, mysql.getDatabaseName(), "", "contract_test").size());
            DatabaseObject exact = provider.metadata().findObject(
                    session, mysql.getDatabaseName(), "", "inspector_long_ddl");
            assertEquals(DatabaseObjectType.TABLE, exact.type());
            assertFalse(provider.metadata().listIndexes(
                    session, mysql.getDatabaseName(), "", exact.name()).isEmpty());
            assertEquals(2, provider.metadata().listPartitions(
                    session, mysql.getDatabaseName(), "", exact.name(), "", 200).items().size());
            java.io.StringWriter fullDdl = new java.io.StringWriter();
            provider.metadata().writeRebuildDdl(session, exact, fullDdl);
            assertTrue(fullDdl.toString().length() > 10_000);
            assertTrue(fullDdl.toString().contains("inspector_column_399_with_long_name"));
            session.rollback();
            try (java.sql.Statement statement = session.jdbcConnection().createStatement();
                 java.sql.ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM contract_test")) {
                result.next();
                assertEquals(0, result.getInt(1));
            }
            assertFalse(session.jdbcConnection().getAutoCommit());

            try (java.sql.Statement statement = session.jdbcConnection().createStatement()) {
                statement.executeUpdate("INSERT INTO contract_test VALUES (1, 'locked'), (2, 'free')");
            }
            session.commit();
            try (com.dbstudio.spi.DatabaseSession contender = provider.connections().connect(profile, password)) {
                try (java.sql.Statement locker = session.jdbcConnection().createStatement();
                     java.sql.ResultSet locked = locker.executeQuery(
                             "SELECT id FROM contract_test WHERE id = 1 FOR UPDATE")) {
                    assertTrue(locked.next());
                }
                try (java.sql.Statement statement = contender.jdbcConnection().createStatement()) {
                    try (java.sql.ResultSet skipped = statement.executeQuery(
                            "SELECT id FROM contract_test WHERE id = 1 FOR UPDATE SKIP LOCKED")) {
                        assertFalse(skipped.next());
                    }
                    SQLException nowait = assertThrows(SQLException.class, () -> statement.executeQuery(
                            "SELECT id FROM contract_test WHERE id = 1 FOR UPDATE NOWAIT"));
                    assertTrue(nowait.getErrorCode() != 0 || nowait.getSQLState() != null);
                }
                session.rollback();
                try (java.sql.Statement statement = contender.jdbcConnection().createStatement()) {
                    assertEquals(1, statement.executeUpdate(
                            "UPDATE contract_test SET name = 'after-release' WHERE id = 1"));
                }
                contender.rollback();
            }

            ExecutorService blockedWorker = Executors.newSingleThreadExecutor();
            ExecutorService abortWorker = Executors.newCachedThreadPool();
            try (com.dbstudio.spi.DatabaseSession blocked = provider.connections().connect(profile, password)) {
                try (java.sql.Statement locker = session.jdbcConnection().createStatement();
                     java.sql.ResultSet locked = locker.executeQuery(
                             "SELECT id FROM contract_test WHERE id = 1 FOR UPDATE")) {
                    assertTrue(locked.next());
                }
                Future<?> waiting = blockedWorker.submit(() -> {
                    try (java.sql.Statement statement = blocked.jdbcConnection().createStatement();
                         java.sql.ResultSet ignored = statement.executeQuery(
                                 "SELECT id FROM contract_test WHERE id = 1 FOR UPDATE")) {
                        return null;
                    }
                });
                assertThrows(TimeoutException.class, () -> waiting.get(300, TimeUnit.MILLISECONDS));
                blocked.jdbcConnection().abort(abortWorker);
                assertThrows(ExecutionException.class, () -> waiting.get(10, TimeUnit.SECONDS));
                session.rollback();
                try (com.dbstudio.spi.DatabaseSession replacement = provider.connections().connect(profile, password);
                     java.sql.Statement statement = replacement.jdbcConnection().createStatement();
                     java.sql.ResultSet result = statement.executeQuery("SELECT 1")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            } finally {
                session.rollback();
                blockedWorker.shutdownNow();
                abortWorker.shutdownNow();
            }
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
