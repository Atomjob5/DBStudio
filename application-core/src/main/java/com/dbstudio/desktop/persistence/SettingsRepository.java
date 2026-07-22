package com.dbstudio.desktop.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SQLite 用户设置仓库；只保存白名单键和值，不保存密码或令牌。 */
public final class SettingsRepository {
    private static final Logger LOG = LoggerFactory.getLogger(SettingsRepository.class);
    private final Connection connection;
    public SettingsRepository(AppDatabase database) { this.connection = database.connection(); }

    public synchronized Optional<String> get(String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT setting_value FROM app_setting WHERE setting_key=?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                Optional<String> value = resultSet.next() ? Optional.of(resultSet.getString(1)) : Optional.<String>empty();
                LOG.debug("读取应用设置 key={} exists={}", key, value.isPresent());
                return value;
            }
        }
    }

    public synchronized void put(String key, String value) throws SQLException {
        String sql = "INSERT INTO app_setting(setting_key, setting_value) VALUES (?, ?) "
                + "ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
        LOG.info("更新应用设置 key={}", key);
    }
}
