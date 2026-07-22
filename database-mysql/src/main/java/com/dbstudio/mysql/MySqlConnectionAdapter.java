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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MySQL JDBC 连接适配器：密码只通过 Properties 传给驱动，不拼接到 URL 或日志。 */
public final class MySqlConnectionAdapter implements ConnectionAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(MySqlConnectionAdapter.class);
    static final int DEFAULT_PORT = 3306;
    static final int DEFAULT_TIMEOUT_SECONDS = 10;

    @Override
    public ConnectionTestResult test(ConnectionProfile profile, char[] password) {
        Instant started = Instant.now();
        LOG.info("MySQL连接测试开始 profile={} host={} port={}", profile.id(), profile.setting("host"),
                profile.intSetting("port", DEFAULT_PORT));
        try (DatabaseSession session = connect(profile, password);
             Statement statement = session.jdbcConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT VERSION()")) {
            resultSet.next();
            ConnectionTestResult result = new ConnectionTestResult(
                    true,
                    "连接成功",
                    resultSet.getString(1),
                    Duration.between(started, Instant.now()));
            LOG.info("MySQL连接测试完成 profile={} success=true durationMs={}", profile.id(),
                    result.latency().toMillis());
            return result;
        } catch (SQLException | IllegalArgumentException exception) {
            LOG.warn("MySQL连接测试失败 profile={} reason={}", profile.id(), sanitize(exception.getMessage()));
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
        properties.setProperty("useOldAliasMetadataBehavior", "false");
        properties.setProperty("serverTimezone", "UTC");
        properties.setProperty("sslMode", "DISABLED");

        long started = System.nanoTime();
        java.sql.Connection connection = DriverManager.getConnection(buildJdbcUrl(profile), properties);
        connection.setAutoCommit(false);
        String catalog = profile.setting("database");
        if (!catalog.trim().isEmpty()) {
            connection.setCatalog(catalog);
        }
        LOG.info("MySQL JDBC连接建立 profile={} durationMs={}", profile.id(),
                (System.nanoTime() - started) / 1_000_000L);
        return new JdbcDatabaseSession(connection);
    }

    @Override
    public void resetSession(DatabaseSession session, ConnectionProfile profile) throws SQLException {
        ConnectionAdapter.super.resetSession(session, profile);
        String catalog = profile.setting("database");
        if (!catalog.trim().isEmpty()) session.jdbcConnection().setCatalog(catalog);
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
        // Catalog 通过 Connection.setCatalog 设置，避免把用户输入的数据库名拼进 JDBC URL。
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
