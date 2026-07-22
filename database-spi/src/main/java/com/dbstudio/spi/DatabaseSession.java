package com.dbstudio.spi;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 编辑器使用的数据库会话。
 *
 * <p>会话封装 JDBC 连接并定义提交、回滚和关闭边界；实现类不得把密码或连接属性暴露给上层日志。</p>
 */
public interface DatabaseSession extends AutoCloseable {
    Connection jdbcConnection();

    String currentCatalog() throws SQLException;

    default String currentSchema() throws SQLException {
        String schema = jdbcConnection().getSchema();
        return schema == null ? "" : schema;
    }

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
