package com.dbstudio.spi;

import java.sql.SQLException;

public interface ConnectionAdapter {
    ConnectionTestResult test(ConnectionProfile profile, char[] password);

    DatabaseSession connect(ConnectionProfile profile, char[] password) throws SQLException;

    /** Restores a borrowed JDBC session to the provider's configured baseline before pooling it. */
    default void resetSession(DatabaseSession session, ConnectionProfile profile) throws SQLException {
        session.rollback();
        session.jdbcConnection().clearWarnings();
        session.jdbcConnection().setReadOnly(false);
        if (session.jdbcConnection().getAutoCommit()) session.jdbcConnection().setAutoCommit(false);
    }
}
