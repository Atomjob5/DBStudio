package com.dbstudio.oracle.common;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseSession;
import java.sql.SQLException;
import java.sql.Statement;

public final class OracleSessionSupport {
    private OracleSessionSupport() { }

    public static void initialize(DatabaseSession session, ConnectionProfile profile) throws SQLException {
        session.jdbcConnection().setAutoCommit(false);
        applyDefaultSchema(session, profile);
    }

    public static void resetBaseline(DatabaseSession session, ConnectionProfile profile) throws SQLException {
        session.rollback();
        session.jdbcConnection().clearWarnings();
        session.jdbcConnection().setReadOnly(false);
        if (session.jdbcConnection().getAutoCommit()) session.jdbcConnection().setAutoCommit(false);
        applyDefaultSchema(session, profile);
    }

    public static void applyDefaultSchema(DatabaseSession session, ConnectionProfile profile) throws SQLException {
        String schema = profile.setting("schema").trim();
        if (schema.isEmpty()) schema = usernameSchema(profile.setting("username").trim());
        if (schema.isEmpty()) return;
        try (Statement statement = session.jdbcConnection().createStatement()) {
            statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + quoteIdentifier(schema));
        }
    }

    private static String usernameSchema(String username) {
        int tenantSeparator = username.indexOf('@');
        int clusterSeparator = username.indexOf('#');
        int separator = tenantSeparator < 0 ? clusterSeparator
                : clusterSeparator < 0 ? tenantSeparator : Math.min(tenantSeparator, clusterSeparator);
        return separator < 0 ? username : username.substring(0, separator);
    }

    public static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new IllegalArgumentException("Schema不能为空");
        }
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
