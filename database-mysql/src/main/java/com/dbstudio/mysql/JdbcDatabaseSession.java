package com.dbstudio.mysql;

import com.dbstudio.spi.DatabaseSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

final class JdbcDatabaseSession implements DatabaseSession {
    private final Connection connection;

    JdbcDatabaseSession(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public Connection jdbcConnection() {
        return connection;
    }

    @Override
    public String currentCatalog() throws SQLException {
        return connection.getCatalog();
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
