package com.dbstudio.desktop.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.dbstudio.desktop.logging.SqlLogSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 查询历史仓库；SQL 正文仅用于用户历史记录，日志只输出指纹和执行结果统计。 */
public final class QueryHistoryRepository {
    private static final Logger LOG = LoggerFactory.getLogger(QueryHistoryRepository.class);
    private final Connection connection;
    public QueryHistoryRepository(AppDatabase database) { this.connection = database.connection(); }

    public synchronized void add(QueryHistoryEntry entry) throws SQLException {
        String sql = "INSERT INTO query_history(profile_id, catalog_name, sql_text, executed_at, "
                + "duration_ms, status, row_count, error_message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.profileId() == null ? null : entry.profileId().toString());
            statement.setString(2, entry.catalog());
            statement.setString(3, entry.sql());
            statement.setString(4, entry.executedAt().toString());
            statement.setLong(5, entry.durationMs());
            statement.setString(6, entry.status());
            statement.setLong(7, entry.rowCount());
            statement.setString(8, entry.errorMessage());
            statement.executeUpdate();
        }
        trim(5_000);
        LOG.debug("写入查询历史 profile={} status={} rows={} sqlFingerprint={}", entry.profileId(),
                entry.status(), entry.rowCount(), SqlLogSupport.fingerprint(entry.sql()));
    }

    public synchronized List<QueryHistoryEntry> recent(int limit) throws SQLException {
        List<QueryHistoryEntry> entries = new ArrayList<QueryHistoryEntry>();
        String sql = "SELECT profile_id, catalog_name, sql_text, executed_at, duration_ms, status, "
                + "row_count, error_message FROM query_history ORDER BY id DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, limit));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String profileId = resultSet.getString("profile_id");
                    entries.add(new QueryHistoryEntry(profileId == null ? null : UUID.fromString(profileId),
                            resultSet.getString("catalog_name"), resultSet.getString("sql_text"),
                            Instant.parse(resultSet.getString("executed_at")), resultSet.getLong("duration_ms"),
                            resultSet.getString("status"), resultSet.getLong("row_count"),
                            resultSet.getString("error_message")));
                }
            }
        }
        return entries;
    }

    private void trim(int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM query_history WHERE id NOT IN "
                        + "(SELECT id FROM query_history ORDER BY id DESC LIMIT ?)")) {
            statement.setInt(1, limit);
            statement.executeUpdate();
        }
    }

    public static final class QueryHistoryEntry {
        private final UUID profileId;
        private final String catalog;
        private final String sql;
        private final Instant executedAt;
        private final long durationMs;
        private final String status;
        private final long rowCount;
        private final String errorMessage;

        public QueryHistoryEntry(UUID profileId, String catalog, String sql, Instant executedAt,
                                 long durationMs, String status, long rowCount, String errorMessage) {
            this.profileId = profileId; this.catalog = catalog; this.sql = sql; this.executedAt = executedAt;
            this.durationMs = durationMs; this.status = status; this.rowCount = rowCount;
            this.errorMessage = errorMessage;
        }
        public UUID profileId() { return profileId; }
        public String catalog() { return catalog; }
        public String sql() { return sql; }
        public Instant executedAt() { return executedAt; }
        public long durationMs() { return durationMs; }
        public String status() { return status; }
        public long rowCount() { return rowCount; }
        public String errorMessage() { return errorMessage; }
    }
}
