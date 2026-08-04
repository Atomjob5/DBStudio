package com.dbstudio.desktop.csv;

import com.dbstudio.desktop.query.QueryRunner;
import com.dbstudio.desktop.query.StatementResult;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.SqlLogCategory;
import com.dbstudio.spi.SqlLogging;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CSV导入导出服务。
 *
 * <p>文件内容只在本地流式处理，批量导入使用保存点保证失败可回滚；日志只记录文件元数据和行数，
 * 不记录文件内容或密码。</p>
 */
public final class CsvService {
    private static final Logger LOG = LoggerFactory.getLogger(CsvService.class);
    public static final String NULL_VALUE = "\\N";
    public static final int DEFAULT_BATCH_SIZE = 500;

    public Preview preview(Path file, Charset charset, char delimiter, int maxRows) throws IOException {
        LOG.debug("CSV预览开始 file={} charset={} delimiter={} maxRows={}", file, charset.name(), delimiter, maxRows);
        try (BufferedReader reader = Files.newBufferedReader(file, charset);
             CSVParser parser = csvFormat(delimiter).parse(reader)) {
            List<String> headers = new ArrayList<String>(parser.getHeaderNames());
            List<List<String>> rows = new ArrayList<List<String>>();
            for (CSVRecord record : parser) {
                if (rows.size() >= Math.max(1, maxRows)) break;
                List<String> row = new ArrayList<String>(headers.size());
                for (String header : headers) row.add(record.isMapped(header) ? record.get(header) : "");
                rows.add(row);
            }
            LOG.info("CSV预览完成 file={} columns={} rows={}", file, headers.size(), rows.size());
            return new Preview(headers, rows);
        }
    }

    public long importFile(DatabaseSession session, SqlDialect dialect, String catalog, String table,
                           Path file, Charset charset, char delimiter,
                           Map<String, String> sourceToTarget) throws SQLException, IOException {
        return importFile(session, dialect, catalog, "", table, file, charset, delimiter, sourceToTarget,
                new LongConsumer() { @Override public void accept(long value) { } });
    }

    public long importFile(DatabaseSession session, SqlDialect dialect, String catalog, String table,
                           Path file, Charset charset, char delimiter, Map<String, String> sourceToTarget,
                           LongConsumer progress) throws SQLException, IOException {
        return importFile(session, dialect, catalog, "", table, file, charset, delimiter, sourceToTarget, progress);
    }

    public long importFile(DatabaseSession session, SqlDialect dialect, String catalog, String schema, String table,
                           Path file, Charset charset, char delimiter, Map<String, String> sourceToTarget,
                           LongConsumer progress) throws SQLException, IOException {
        try (SqlLogging.Scope ignored = SqlLogging.scope(SqlLogCategory.TRANSFER)) {
        if (sourceToTarget.isEmpty()) throw new IllegalArgumentException("至少映射一个字段");
        long started = System.nanoTime();
        LOG.info("CSV导入开始 target={} file={} mappingCount={} delimiter={}",
                dialect.qualifiedName(catalog, schema, table), file, sourceToTarget.size(), delimiter);
        Map<String, String> mapping = new LinkedHashMap<String, String>(sourceToTarget);
        String target = dialect.qualifiedName(catalog, schema, table);
        List<String> columns = mapping.values().stream().map(dialect::quoteIdentifier).collect(Collectors.toList());
        List<String> placeholders = mapping.values().stream().map(value -> "?").collect(Collectors.toList());
        String sql = "INSERT INTO " + target + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", placeholders) + ")";

        Savepoint savepoint = session.jdbcConnection().setSavepoint("dbstudio_csv_import");
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, charset);
             CSVParser parser = csvFormat(delimiter).parse(reader);
             PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
            int[] parameterTypes = parameterTypes(statement, mapping.size());
            int pending = 0;
            for (CSVRecord record : parser) {
                int parameter = 1;
                for (String source : mapping.keySet()) {
                    String value = record.get(source);
                    bindValue(statement, parameter, value, parameterTypes[parameter - 1]);
                    parameter++;
                }
                statement.addBatch();
                pending++;
                if (pending >= DEFAULT_BATCH_SIZE) {
                    count += successfulRows(statement.executeBatch());
                    progress.accept(count);
                    pending = 0;
                }
            }
            if (pending > 0) {
                count += successfulRows(statement.executeBatch());
                progress.accept(count);
            }
            session.jdbcConnection().releaseSavepoint(savepoint);
            LOG.info("CSV导入完成 target={} file={} rows={} durationMs={}", target, file, count,
                    (System.nanoTime() - started) / 1_000_000L);
            return count;
        } catch (SQLException exception) {
            LOG.warn("CSV导入失败 target={} file={} rows={}，已回滚保存点", target, file, count, exception);
            session.jdbcConnection().rollback(savepoint);
            throw exception;
        } catch (IOException exception) {
            LOG.warn("CSV导入读取失败 target={} file={} rows={}，已回滚保存点", target, file, count, exception);
            session.jdbcConnection().rollback(savepoint);
            throw exception;
        } catch (RuntimeException exception) {
            session.jdbcConnection().rollback(savepoint);
            throw exception;
        }
        }
    }

    public void exportLoadedResult(StatementResult result, Path file, Charset charset, char delimiter)
            throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, charset);
             CSVPrinter printer = new CSVPrinter(writer, exportFormat(delimiter))) {
            writeLoadedResult(result, printer);
        }
    }

    public void exportLoadedResult(StatementResult result, Writer writer, char delimiter) throws IOException {
        try (CSVPrinter printer = new CSVPrinter(writer, exportFormat(delimiter))) {
            writeLoadedResult(result, printer);
        }
    }

    private void writeLoadedResult(StatementResult result, CSVPrinter printer) throws IOException {
            printer.printRecord(result.columns());
            for (List<String> row : result.rows()) {
                List<String> values = new ArrayList<String>(row.size());
                for (String value : row) values.add(exportValue(value));
                printer.printRecord(values);
            }
    }

    public long exportQuery(DatabaseSession session, String sql, Path file, Charset charset, char delimiter)
            throws SQLException, IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, charset)) {
            return exportQuery(session, sql, writer, delimiter,
                    new LongConsumer() { @Override public void accept(long value) { } });
        }
    }

    public long exportQuery(DatabaseSession session, String sql, Path file, Charset charset, char delimiter,
                            LongConsumer progress) throws SQLException, IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, charset)) {
            return exportQuery(session, sql, writer, delimiter, progress);
        }
    }

    /** 直接流式写入 HTTP 输出，不把完整结果集保留在内存。 */
    public long exportQuery(DatabaseSession session, String sql, Writer writer, char delimiter,
                            LongConsumer progress) throws SQLException, IOException {
        try (SqlLogging.Scope ignored = SqlLogging.scope(SqlLogCategory.TRANSFER)) {
        long started = System.nanoTime();
        LOG.info("CSV完整导出开始 sqlFingerprint={} delimiter={}",
                com.dbstudio.desktop.logging.SqlLogSupport.fingerprint(sql), delimiter);
        try (Statement statement = session.jdbcConnection().createStatement();
             CSVPrinter printer = new CSVPrinter(writer, exportFormat(delimiter))) {
            statement.setFetchSize(QueryRunner.JDBC_FETCH_SIZE);
            if (!statement.execute(sql)) throw new SQLException("该语句没有返回结果集");
            try (ResultSet resultSet = statement.getResultSet()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<String> headers = new ArrayList<String>(columnCount);
                for (int index = 1; index <= columnCount; index++) headers.add(metadata.getColumnLabel(index));
                printer.printRecord(headers);
                long rows = 0;
                while (resultSet.next()) {
                    List<String> values = new ArrayList<String>(columnCount);
                    for (int index = 1; index <= columnCount; index++) {
                        values.add(exportValue(QueryRunner.displayValue(resultSet.getObject(index))));
                    }
                    printer.printRecord(values);
                    rows++;
                    if (rows % DEFAULT_BATCH_SIZE == 0) { printer.flush(); progress.accept(rows); }
                }
                progress.accept(rows);
                LOG.info("CSV完整导出完成 sqlFingerprint={} rows={} durationMs={}",
                        com.dbstudio.desktop.logging.SqlLogSupport.fingerprint(sql), rows,
                        (System.nanoTime() - started) / 1_000_000L);
                return rows;
            }
        }
        }
    }

    private static CSVFormat csvFormat(char delimiter) {
        return CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false).get();
    }
    private static CSVFormat exportFormat(char delimiter) {
        return CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setRecordSeparator(System.lineSeparator()).get();
    }
    private static long successfulRows(int[] batchCounts) {
        long count = 0;
        for (int batchCount : batchCounts) count += batchCount >= 0 ? batchCount : 1;
        return count;
    }

    private static int[] parameterTypes(PreparedStatement statement, int count) {
        int[] result = new int[count];
        java.util.Arrays.fill(result, Types.VARCHAR);
        try {
            ParameterMetaData metadata = statement.getParameterMetaData();
            for (int index = 1; index <= count; index++) result[index - 1] = metadata.getParameterType(index);
        } catch (SQLException ignored) {
            // 部分驱动在执行前不提供参数元数据，此时退回字符串绑定，避免导入流程被驱动能力阻断。
        }
        return result;
    }

    private static void bindValue(PreparedStatement statement, int parameter, String value, int jdbcType)
            throws SQLException {
        if (NULL_VALUE.equals(value)) {
            statement.setNull(parameter, jdbcType == Types.NULL ? Types.VARCHAR : jdbcType);
            return;
        }
        try {
            switch (jdbcType) {
                case Types.TINYINT:
                case Types.SMALLINT:
                case Types.INTEGER:
                case Types.BIGINT:
                case Types.FLOAT:
                case Types.REAL:
                case Types.DOUBLE:
                case Types.NUMERIC:
                case Types.DECIMAL:
                    statement.setBigDecimal(parameter, new BigDecimal(value));
                    return;
                case Types.DATE:
                    statement.setDate(parameter, Date.valueOf(value));
                    return;
                case Types.TIME:
                case Types.TIME_WITH_TIMEZONE:
                    statement.setTime(parameter, Time.valueOf(value));
                    return;
                case Types.TIMESTAMP:
                case Types.TIMESTAMP_WITH_TIMEZONE:
                    statement.setTimestamp(parameter, Timestamp.valueOf(value));
                    return;
                case Types.BIT:
                case Types.BOOLEAN:
                    statement.setBoolean(parameter, "1".equals(value) || "true".equalsIgnoreCase(value)
                            || "yes".equalsIgnoreCase(value));
                    return;
                case Types.BINARY:
                case Types.VARBINARY:
                case Types.LONGVARBINARY:
                case Types.BLOB:
                    statement.setBytes(parameter, binaryValue(value));
                    return;
                case Types.CLOB:
                case Types.NCLOB:
                    statement.setString(parameter, value);
                    return;
                default:
                    statement.setString(parameter, value);
            }
        } catch (IllegalArgumentException exception) {
            throw new SQLException("CSV字段值与目标数据库类型不匹配（参数" + parameter + "，JDBC类型"
                    + jdbcType + "）：" + value, exception);
        }
    }

    private static byte[] binaryValue(String value) {
        String hex = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : "";
        if (!hex.isEmpty() && hex.length() % 2 == 0 && hex.matches("[0-9A-Fa-f]+")) {
            byte[] bytes = new byte[hex.length() / 2];
            for (int index = 0; index < hex.length(); index += 2) {
                bytes[index / 2] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
            }
            return bytes;
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String exportValue(String value) { return value == null ? NULL_VALUE : value; }

    public static final class Preview {
        private final List<String> headers;
        private final List<List<String>> rows;
        public Preview(List<String> headers, List<List<String>> rows) {
            this.headers = Collections.unmodifiableList(new ArrayList<String>(headers));
            List<List<String>> copied = new ArrayList<List<String>>(rows.size());
            for (List<String> row : rows) copied.add(Collections.unmodifiableList(new ArrayList<String>(row)));
            this.rows = Collections.unmodifiableList(copied);
        }
        public List<String> headers() { return headers; }
        public List<List<String>> rows() { return rows; }
    }
}
