package com.dbstudio.spi;

import java.sql.Connection;
import java.sql.SQLException;

public interface DatabaseSession extends AutoCloseable {
    Connection jdbcConnection();

    String currentCatalog() throws SQLException;

    default void commit() throws SQLException {
        jdbcConnection().commit();
    }

    default void rollback() throws SQLException {
        jdbcConnection().rollback();
    }

    default boolean isClosed() throws SQLException {
        return jdbcConnection().isClosed();
    }

    @Override
    void close() throws SQLException;
}
