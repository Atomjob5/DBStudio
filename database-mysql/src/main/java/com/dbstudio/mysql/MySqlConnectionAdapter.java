package com.dbstudio.mysql;

import com.dbstudio.spi.ConnectionAdapter;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.ConnectionTestResult;
import com.dbstudio.spi.DatabaseSession;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;

public final class MySqlConnectionAdapter implements ConnectionAdapter {
    static final int DEFAULT_PORT = 3306;
    static final int DEFAULT_TIMEOUT_SECONDS = 10;

    @Override
    public ConnectionTestResult test(ConnectionProfile profile, char[] password) {
        Instant started = Instant.now();
        try (DatabaseSession session = connect(profile, password);
             Statement statement = session.jdbcConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
            resultSet.next();
            return new ConnectionTestResult(
                    true,
                    "连接成功",
                    resultSet.getString(1),
                    Duration.between(started, Instant.now()));
        } catch (SQLException | IllegalArgumentException exception) {
            return ConnectionTestResult.failure(
                    sanitize(exception.getMessage()),
                    Duration.between(started, Instant.now()));
        }
    }

    @Override
    public DatabaseSession connect(ConnectionProfile profile, char[] password) throws SQLException {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(password, "password");

        Properties properties = new Properties();
        properties.setProperty("user", required(profile, "username"));
        properties.setProperty("password", new String(password));
        properties.setProperty("connectTimeout", Integer.toString(
                profile.intSetting("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS) * 1_000));
        properties.setProperty("socketTimeout", "0");
        properties.setProperty("useUnicode", "true");
        properties.setProperty("characterEncoding", "UTF-8");
        properties.setProperty("useCursorFetch", "true");
        properties.setProperty("useServerPrepStmts", "true");
        properties.setProperty("serverTimezone", "UTC");
        properties.setProperty("sslMode", "DISABLED");

        java.sql.Connection connection = DriverManager.getConnection(buildJdbcUrl(profile), properties);
        connection.setAutoCommit(false);
        String catalog = profile.setting("database");
        if (!catalog.trim().isEmpty()) {
            connection.setCatalog(catalog);
        }
        return new JdbcDatabaseSession(connection);
    }

    public static String buildJdbcUrl(ConnectionProfile profile) {
        String host = required(profile, "host");
        if (host.contains("/") || host.contains("?") || host.contains("#")) {
            throw new IllegalArgumentException("主机名包含非法字符");
        }
        int port = profile.intSetting("port", DEFAULT_PORT);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
        }
        // The catalog is selected with Connection.setCatalog so names never have to be placed in a URL.
        return "jdbc:mysql://" + host + ":" + port + "/";
    }

    private static String required(ConnectionProfile profile, String key) {
        String value = profile.setting(key).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " 不能为空");
        }
        return value;
    }

    private static String sanitize(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "连接失败";
        }
        return message.replaceAll("(?i)(password=)[^&\\s]+", "$1***");
    }
}
