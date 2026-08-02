package com.dbstudio.oracle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Optional Oracle Free/19c/21c release-gate suite configured with DBSTUDIO_ORACLE_* variables. */
class OracleResultEditIntegrationTest {
    @Test void abortsABlockedForUpdateWithoutPreventingANewPhysicalConnection() throws Exception {
        String url = System.getenv("DBSTUDIO_ORACLE_JDBC_URL");
        String user = System.getenv("DBSTUDIO_ORACLE_USERNAME");
        String password = System.getenv("DBSTUDIO_ORACLE_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.trim().isEmpty()
                && user != null && !user.trim().isEmpty() && password != null,
                "set DBSTUDIO_ORACLE_JDBC_URL/USERNAME/PASSWORD to run the Oracle abort gate");
        String table = "DBS_ABORT_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        ExecutorService blockedWorker = Executors.newSingleThreadExecutor();
        ExecutorService abortWorker = Executors.newCachedThreadPool();
        try (Connection locker = DriverManager.getConnection(url, user, password);
             Connection blocked = DriverManager.getConnection(url, user, password)) {
            locker.setAutoCommit(false);
            blocked.setAutoCommit(false);
            try (Statement statement = locker.createStatement()) {
                statement.execute("CREATE TABLE " + table + " (id NUMBER PRIMARY KEY, name VARCHAR2(40))");
                statement.executeUpdate("INSERT INTO " + table + " VALUES (1, 'locked')");
            }
            locker.commit();
            try {
                try (Statement statement = locker.createStatement();
                     ResultSet result = statement.executeQuery(
                             "SELECT id FROM " + table + " WHERE id = 1 FOR UPDATE")) {
                    assertTrue(result.next());
                }
                Future<?> waiting = blockedWorker.submit(() -> {
                    try (Statement statement = blocked.createStatement();
                         ResultSet ignored = statement.executeQuery(
                                 "SELECT id FROM " + table + " WHERE id = 1 FOR UPDATE")) {
                        return null;
                    }
                });
                assertThrows(TimeoutException.class, () -> waiting.get(300, TimeUnit.MILLISECONDS));
                blocked.abort(abortWorker);
                assertThrows(ExecutionException.class, () -> waiting.get(10, TimeUnit.SECONDS));

                try (Connection replacement = DriverManager.getConnection(url, user, password);
                     Statement statement = replacement.createStatement();
                     ResultSet result = statement.executeQuery("SELECT 1 FROM DUAL")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            } finally {
                locker.rollback();
                try (Statement statement = locker.createStatement()) {
                    statement.execute("DROP TABLE " + table + " PURGE");
                }
            }
        } finally {
            blockedWorker.shutdownNow();
            abortWorker.shutdownNow();
        }
    }

    @Test void supportsRowIdTypesAtomicRollbackAndLockConflicts() throws Exception {
        String url = System.getenv("DBSTUDIO_ORACLE_JDBC_URL");
        String user = System.getenv("DBSTUDIO_ORACLE_USERNAME");
        String password = System.getenv("DBSTUDIO_ORACLE_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.trim().isEmpty()
                && user != null && !user.trim().isEmpty() && password != null,
                "set DBSTUDIO_ORACLE_JDBC_URL/USERNAME/PASSWORD to run the Oracle result-edit gate");
        String table = "DBS_EDIT_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12).toUpperCase(Locale.ROOT);
        try (Connection owner = DriverManager.getConnection(url, user, password);
             Connection contender = DriverManager.getConnection(url, user, password)) {
            owner.setAutoCommit(false);
            contender.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("CREATE TABLE " + table + " (id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                        + " name VARCHAR2(100) DEFAULT 'db-default' NOT NULL, amount NUMBER(38,10),"
                        + " happened_at DATE, zoned_at TIMESTAMP WITH TIME ZONE, raw_value RAW(32),"
                        + " text_value CLOB, binary_value BLOB)");
            }
            try {
                String rowId;
                try (CallableStatement statement = owner.prepareCall("BEGIN INSERT INTO " + table
                        + " (name, amount, happened_at, zoned_at, raw_value, text_value, binary_value)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING ROWID INTO ?; END;")) {
                    statement.setString(1, "");
                    statement.setBigDecimal(2, new BigDecimal("99999999999999999999.1234567890"));
                    statement.setTimestamp(3, Timestamp.valueOf("2026-08-01 12:34:56"));
                    statement.setObject(4, OffsetDateTime.parse("2026-08-01T12:34:56+08:00"));
                    statement.setBytes(5, new byte[] { 0x00, 0x0f, (byte) 0xff });
                    statement.setCharacterStream(6, new java.io.StringReader("大字段文本"));
                    statement.setBinaryStream(7, new java.io.ByteArrayInputStream(new byte[] { 1, 2, 3 }));
                    statement.registerOutParameter(8, Types.VARCHAR);
                    SQLException requiredName = assertThrows(SQLException.class, statement::execute);
                    assertTrue(requiredName.getErrorCode() != 0);
                }
                owner.rollback();

                try (CallableStatement statement = owner.prepareCall("BEGIN INSERT INTO " + table
                        + " (name, amount, happened_at, zoned_at, raw_value, text_value, binary_value)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING ROWID INTO ?; END;")) {
                    statement.setString(1, "initial");
                    statement.setBigDecimal(2, new BigDecimal("99999999999999999999.1234567890"));
                    statement.setTimestamp(3, Timestamp.valueOf("2026-08-01 12:34:56"));
                    statement.setObject(4, OffsetDateTime.parse("2026-08-01T12:34:56+08:00"));
                    statement.setBytes(5, new byte[] { 0x00, 0x0f, (byte) 0xff });
                    statement.setCharacterStream(6, new java.io.StringReader("大字段文本"));
                    statement.setBinaryStream(7, new java.io.ByteArrayInputStream(new byte[] { 1, 2, 3 }));
                    statement.registerOutParameter(8, Types.VARCHAR);
                    statement.execute();
                    rowId = statement.getString(8);
                }
                assertNotNull(rowId);
                owner.commit();

                try (PreparedStatement lock = owner.prepareStatement("SELECT ROWIDTOCHAR(t.ROWID), t.name FROM "
                        + table + " t WHERE t.ROWID = CHARTOROWID(?) FOR UPDATE")) {
                    lock.setString(1, rowId);
                    try (ResultSet result = lock.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(rowId, result.getString(1));
                        assertEquals("initial", result.getString(2));
                    }
                }
                try (PreparedStatement blocked = contender.prepareStatement("SELECT name FROM " + table
                        + " WHERE ROWID = CHARTOROWID(?) FOR UPDATE NOWAIT")) {
                    blocked.setString(1, rowId);
                    SQLException conflict = assertThrows(SQLException.class, blocked::executeQuery);
                    assertTrue(Math.abs(conflict.getErrorCode()) == 54
                            || String.valueOf(conflict.getMessage()).toLowerCase(Locale.ROOT).contains("busy"));
                }

                try (PreparedStatement clone = owner.prepareStatement("INSERT INTO " + table
                        + " (id, name, amount, happened_at, zoned_at, raw_value, text_value, binary_value)"
                        + " SELECT ?, ?, amount, happened_at, zoned_at, raw_value, text_value, binary_value"
                        + " FROM " + table + " WHERE ROWID = CHARTOROWID(?)")) {
                    clone.setLong(1, 1001L);
                    clone.setString(2, "cloned-rollback");
                    clone.setString(3, rowId);
                    assertEquals(1, clone.executeUpdate());
                }
                assertClonedLargeValues(owner, table, 1001L, "cloned-rollback");
                owner.rollback();
                try (PreparedStatement absent = contender.prepareStatement("SELECT COUNT(*) FROM " + table
                        + " WHERE id = ?")) {
                    absent.setLong(1, 1001L);
                    try (ResultSet result = absent.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(0, result.getInt(1));
                    }
                }

                try (PreparedStatement update = owner.prepareStatement("UPDATE " + table
                        + " SET name = ?, happened_at = ? WHERE ROWID = CHARTOROWID(?)")) {
                    update.setString(1, "changed");
                    update.setTimestamp(2, Timestamp.valueOf("2026-08-01 23:59:58"));
                    update.setString(3, rowId);
                    assertEquals(1, update.executeUpdate());
                }
                try (Statement continued = owner.createStatement();
                     ResultSet result = continued.executeQuery("SELECT 1 FROM DUAL")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (PreparedStatement invisible = contender.prepareStatement("SELECT name FROM " + table
                        + " WHERE ROWID = CHARTOROWID(?)")) {
                    invisible.setString(1, rowId);
                    try (ResultSet result = invisible.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("initial", result.getString(1));
                    }
                }
                owner.rollback();
                try (PreparedStatement query = owner.prepareStatement("SELECT name, happened_at FROM " + table
                        + " WHERE ROWID = CHARTOROWID(?)")) {
                    query.setString(1, rowId);
                    try (ResultSet result = query.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("initial", result.getString(1));
                        assertEquals(Timestamp.valueOf("2026-08-01 12:34:56"), result.getTimestamp(2));
                    }
                }
                try (PreparedStatement empty = owner.prepareStatement("UPDATE " + table
                        + " SET name = ? WHERE ROWID = CHARTOROWID(?)")) {
                    empty.setString(1, ""); empty.setString(2, rowId);
                    assertThrows(SQLException.class, empty::executeUpdate);
                }
                owner.rollback();

                try (PreparedStatement update = owner.prepareStatement("UPDATE " + table
                        + " SET name = ? WHERE ROWID = CHARTOROWID(?)")) {
                    update.setString(1, "committed-after-query");
                    update.setString(2, rowId);
                    assertEquals(1, update.executeUpdate());
                }
                try (Statement continued = owner.createStatement();
                     ResultSet result = continued.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (PreparedStatement clone = owner.prepareStatement("INSERT INTO " + table
                        + " (id, name, amount, happened_at, zoned_at, raw_value, text_value, binary_value)"
                        + " SELECT ?, ?, amount, happened_at, zoned_at, raw_value, text_value, binary_value"
                        + " FROM " + table + " WHERE ROWID = CHARTOROWID(?)")) {
                    clone.setLong(1, 1002L);
                    clone.setString(2, "cloned-commit");
                    clone.setString(3, rowId);
                    assertEquals(1, clone.executeUpdate());
                }
                owner.commit();
                try (PreparedStatement committed = contender.prepareStatement("SELECT name FROM " + table
                        + " WHERE ROWID = CHARTOROWID(?)")) {
                    committed.setString(1, rowId);
                    try (ResultSet result = committed.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("committed-after-query", result.getString(1));
                    }
                }
                assertClonedLargeValues(contender, table, 1002L, "cloned-commit");
            } finally {
                owner.rollback();
                try (Statement statement = owner.createStatement()) {
                    statement.execute("DROP TABLE " + table + " PURGE");
                }
            }
        }
    }

    private static void assertClonedLargeValues(Connection connection, String table, long id,
                                                 String expectedName) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT name, raw_value, text_value, binary_value"
                + " FROM " + table + " WHERE id = ?")) {
            query.setLong(1, id);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(expectedName, result.getString(1));
                assertArrayEquals(new byte[] { 0x00, 0x0f, (byte) 0xff }, result.getBytes(2));
                assertEquals("大字段文本", result.getString(3));
                assertArrayEquals(new byte[] { 1, 2, 3 }, result.getBytes(4));
            }
        }
    }
}
