package com.dbstudio.oceanbase.oracle;

import com.dbstudio.oracle.common.OracleJdbcSession;
import com.dbstudio.oracle.common.OracleSessionSupport;
import com.dbstudio.spi.ConnectionAdapter;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.ConnectionTestResult;
import com.dbstudio.spi.DatabaseSession;
import java.sql.Connection;
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

/** OceanBase Oracle 模式连接适配器，建连后额外探测租户是否支持 Oracle 语义。 */
public final class OceanBaseOracleConnectionAdapter implements ConnectionAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(OceanBaseOracleConnectionAdapter.class);
    static final int DEFAULT_PORT = 2881;

    @Override public ConnectionTestResult test(ConnectionProfile profile, char[] password) {
        Instant started = Instant.now();
        LOG.info("OceanBase Oracle连接测试开始 profile={} host={} port={}", profile.id(), profile.setting("host"),
                profile.intSetting("port", DEFAULT_PORT));
        try (DatabaseSession session = connect(profile, password)) {
            ConnectionTestResult result = new ConnectionTestResult(true, "连接成功（Oracle模式）",
                    session.jdbcConnection().getMetaData().getDatabaseProductVersion(),
                    Duration.between(started, Instant.now()));
            LOG.info("OceanBase Oracle连接测试完成 profile={} success=true durationMs={}", profile.id(),
                    result.latency().toMillis());
            return result;
        } catch (SQLException | IllegalArgumentException exception) {
            LOG.warn("OceanBase Oracle连接测试失败 profile={} reason={}", profile.id(), sanitize(exception.getMessage()));
            return ConnectionTestResult.failure(sanitize(exception.getMessage()), Duration.between(started, Instant.now()));
        }
    }

    @Override public DatabaseSession connect(ConnectionProfile profile, char[] password) throws SQLException {
        Objects.requireNonNull(profile, "profile"); Objects.requireNonNull(password, "password");
        try { Class.forName("com.oceanbase.jdbc.Driver"); }
        catch (ClassNotFoundException exception) { throw new SQLException("OceanBase Connector/J未安装", exception); }
        Properties properties = new Properties();
        properties.setProperty("user", required(profile, "username"));
        properties.setProperty("password", new String(password));
        properties.setProperty("connectTimeout", Integer.toString(profile.intSetting("timeoutSeconds", 10) * 1000));
        properties.setProperty("socketTimeout", "0");
        properties.setProperty("compatibleOjdbcVersion", "8");
        properties.setProperty("useUnicode", "true");
        properties.setProperty("characterEncoding", "UTF-8");
        long started = System.nanoTime();
        Connection connection = DriverManager.getConnection(buildJdbcUrl(profile), properties);
        OracleJdbcSession session = new OracleJdbcSession(connection);
        try {
            verifyOracleMode(session);
            OracleSessionSupport.initialize(session, profile);
            LOG.info("OceanBase Oracle JDBC连接建立 profile={} durationMs={}", profile.id(),
                    (System.nanoTime() - started) / 1_000_000L);
            return session;
        } catch (SQLException exception) { connection.close(); throw exception; }
    }

    @Override public void resetSession(DatabaseSession session, ConnectionProfile profile) throws SQLException {
        OracleSessionSupport.resetBaseline(session, profile);
    }

    public static String buildJdbcUrl(ConnectionProfile profile) {
        String host = safeHost(required(profile, "host"));
        int port = profile.intSetting("port", DEFAULT_PORT);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须在1到65535之间");
        String database = required(profile, "database");
        if (database.contains("/") || database.contains("?") || database.contains("#")) {
            throw new IllegalArgumentException("数据库/服务名包含非法字符");
        }
        return "jdbc:oceanbase:oracle://" + host + ":" + port + "/" + database;
    }

    private static void verifyOracleMode(DatabaseSession session) throws SQLException {
        try (Statement statement = session.jdbcConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL")) {
            if (!result.next()) throw new SQLException("无法确认OceanBase租户模式");
        } catch (SQLException exception) {
            throw new SQLException("目标OceanBase租户不是Oracle模式，或当前账号无权访问DUAL", exception);
        }
    }
    private static String required(ConnectionProfile profile, String key) {
        String value = profile.setting(key).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + "不能为空");
        return value;
    }
    private static String safeHost(String host) {
        if (host.contains("/") || host.contains("?") || host.contains("#") || host.contains(":")) {
            throw new IllegalArgumentException("主机名包含非法字符");
        }
        return host;
    }
    private static String sanitize(String message) {
        return message == null || message.trim().isEmpty() ? "连接失败"
                : message.replaceAll("(?i)(password=)[^&\\s]+", "$1***");
    }
}
