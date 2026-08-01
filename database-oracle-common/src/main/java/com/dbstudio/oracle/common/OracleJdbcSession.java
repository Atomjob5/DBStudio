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
        SQLException failure = null;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL")) {
            String schema = result.next() ? value(result.getString(1)).trim() : "";
            if (!schema.isEmpty()) return schema;
        } catch (SQLException exception) {
            failure = exception;
        }
        try {
            String schema = value(connection.getSchema()).trim();
            if (!schema.isEmpty()) return schema;
        } catch (SQLException exception) {
            failure = merge(failure, exception);
        }
        try {
            String username = value(connection.getMetaData().getUserName()).trim();
            int tenantSeparator = username.indexOf('@');
            int clusterSeparator = username.indexOf('#');
            int separator = tenantSeparator < 0 ? clusterSeparator
                    : clusterSeparator < 0 ? tenantSeparator : Math.min(tenantSeparator, clusterSeparator);
            String schema = separator < 0 ? username : username.substring(0, separator);
            if (!schema.trim().isEmpty()) return schema.trim();
        } catch (SQLException exception) {
            failure = merge(failure, exception);
        }
        if (failure != null) throw failure;
        return "";
    }

    @Override public void close() throws SQLException { connection.close(); }

    private static String value(String value) { return value == null ? "" : value; }

    private static SQLException merge(SQLException first, SQLException next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }
}
