package com.dbstudio.oracle.common;

import com.dbstudio.spi.DatabaseSession;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

public final class OracleJdbcSession implements DatabaseSession {
    private final Connection connection;

    public OracleJdbcSession(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override public Connection jdbcConnection() { return connection; }
    @Override public String currentCatalog() { return ""; }

    @Override public String currentSchema() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL")) {
            return result.next() ? value(result.getString(1)) : "";
        } catch (SQLException ignored) {
            String schema = connection.getSchema();
            return schema == null ? "" : schema;
        }
    }

    @Override public void close() throws SQLException { connection.close(); }

    private static String value(String value) { return value == null ? "" : value; }
}
