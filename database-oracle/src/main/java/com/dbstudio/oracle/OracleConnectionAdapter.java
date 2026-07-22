package com.dbstudio.oracle;

import com.dbstudio.oracle.common.OracleJdbcSession;
import com.dbstudio.oracle.common.OracleSessionSupport;
import com.dbstudio.spi.ConnectionAdapter;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.ConnectionTestResult;
import com.dbstudio.spi.DatabaseSession;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;

public final class OracleConnectionAdapter implements ConnectionAdapter {
    static final int DEFAULT_PORT = 1521;
    static final int DEFAULT_TIMEOUT_SECONDS = 10;

    @Override public ConnectionTestResult test(ConnectionProfile profile, char[] password) {
        Instant started = Instant.now();
        try (DatabaseSession session = connect(profile, password)) {
            return new ConnectionTestResult(true, "连接成功",
                    session.jdbcConnection().getMetaData().getDatabaseProductVersion(),
                    Duration.between(started, Instant.now()));
        } catch (SQLException | IllegalArgumentException exception) {
            return ConnectionTestResult.failure(sanitize(exception.getMessage()),
                    Duration.between(started, Instant.now()));
        }
    }

    @Override public DatabaseSession connect(ConnectionProfile profile, char[] password) throws SQLException {
        Objects.requireNonNull(profile, "profile"); Objects.requireNonNull(password, "password");
        try { Class.forName("oracle.jdbc.OracleDriver"); }
        catch (ClassNotFoundException exception) { throw new SQLException("Oracle JDBC驱动未安装", exception); }
        Properties properties = new Properties();
        properties.setProperty("user", required(profile, "username"));
        properties.setProperty("password", new String(password));
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(
                profile.intSetting("timeoutSeconds", DEFAULT_TIMEOUT_SECONDS) * 1000));
        Connection connection = DriverManager.getConnection(buildJdbcUrl(profile), properties);
        OracleJdbcSession session = new OracleJdbcSession(connection);
        try { OracleSessionSupport.initialize(session, profile); return session; }
        catch (SQLException exception) { connection.close(); throw exception; }
    }

    @Override public void resetSession(DatabaseSession session, ConnectionProfile profile) throws SQLException {
        OracleSessionSupport.resetBaseline(session, profile);
    }

    public static String buildJdbcUrl(ConnectionProfile profile) {
        String host = safeHost(required(profile, "host"));
        int port = port(profile, DEFAULT_PORT);
        String target = required(profile, "service");
        if (target.contains("/") || target.contains("?") || target.contains("#") || target.contains(":")) {
            throw new IllegalArgumentException("Service Name/SID包含非法字符");
        }
        return "sid".equalsIgnoreCase(profile.setting("connectionMode"))
                ? "jdbc:oracle:thin:@" + host + ":" + port + ":" + target
                : "jdbc:oracle:thin:@//" + host + ":" + port + "/" + target;
    }

    static String required(ConnectionProfile profile, String key) {
        String value = profile.setting(key).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + "不能为空");
        return value;
    }
    static int port(ConnectionProfile profile, int defaultPort) {
        int port = profile.intSetting("port", defaultPort);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口必须在1到65535之间");
        return port;
    }
    static String safeHost(String host) {
        if (host.contains("/") || host.contains("?") || host.contains("#") || host.contains(":")) {
            throw new IllegalArgumentException("主机名包含非法字符");
        }
        return host;
    }
    static String sanitize(String message) {
        return message == null || message.trim().isEmpty() ? "连接失败"
                : message.replaceAll("(?i)(password=)[^&\\s]+", "$1***");
    }
}
