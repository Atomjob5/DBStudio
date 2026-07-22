package com.dbstudio.spi;

import java.sql.SQLException;

/** 数据库连接生命周期适配器，负责测试、创建会话以及归还连接前的基线重置。 */
public interface ConnectionAdapter {
    ConnectionTestResult test(ConnectionProfile profile, char[] password);

    DatabaseSession connect(ConnectionProfile profile, char[] password) throws SQLException;

    /**
     * 将借出的 JDBC 会话恢复到 Provider 的基线状态后再放回队列。
     * 默认实现回滚事务、清理警告并关闭只读状态；特殊数据库可覆盖此方法恢复 Schema 或 Catalog。
     */
    default void resetSession(DatabaseSession session, ConnectionProfile profile) throws SQLException {
        session.rollback();
        session.jdbcConnection().clearWarnings();
        session.jdbcConnection().setReadOnly(false);
        if (session.jdbcConnection().getAutoCommit()) session.jdbcConnection().setAutoCommit(false);
    }
}
