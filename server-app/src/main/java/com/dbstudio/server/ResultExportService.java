package com.dbstudio.server;

import com.dbstudio.desktop.csv.CsvService;
import com.dbstudio.desktop.query.QueryRunner;
import com.dbstudio.desktop.query.ResultMutationTarget;
import com.dbstudio.desktop.query.StatementResult;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlDialect;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** 流式生成结果集的 CSV、Excel 和 INSERT SQL 文件。 */
public final class ResultExportService {
    private static final int EXCEL_DATA_ROWS_PER_SHEET = SpreadsheetVersion.EXCEL2007.getMaxRows() - 1;
    private static final int EXCEL_COLUMN_LIMIT = SpreadsheetVersion.EXCEL2007.getMaxColumns();

    private final CsvService csv;

    public ResultExportService(CsvService csv) {
        this.csv = csv;
    }

    public void writeLoaded(StatementResult result, OutputStream output, String format,
                            List<Integer> rowIndices, List<Integer> columnIndices, SqlDialect dialect)
            throws IOException {
        if ("csv".equals(format)) {
            writeLoadedCsv(result, output, rowIndices, columnIndices);
            return;
        }
        if ("excel".equals(format)) {
            writeLoadedExcel(result, output, rowIndices, columnIndices);
            return;
        }
        if ("sql".equals(format)) {
            writeLoadedSql(result, output, rowIndices, columnIndices, dialect);
            return;
        }
        throw new IllegalArgumentException("不支持的导出格式：" + format);
    }

    public void writeFull(DatabaseSession session, StatementResult source, OutputStream output,
                           String format, SqlDialect dialect) throws SQLException, IOException {
        if ("csv".equals(format)) {
            csv.exportQuery(session, source.sql(), new OutputStreamWriter(output, StandardCharsets.UTF_8), ',',
                    value -> { });
            return;
        }
        if ("excel".equals(format)) {
            writeFullExcel(session, source, output);
            return;
        }
        if ("sql".equals(format)) {
            writeFullSql(session, source, output, dialect);
            return;
        }
        throw new IllegalArgumentException("不支持的导出格式：" + format);
    }

    public boolean canExportSql(StatementResult result, List<Integer> columnIndices) {
        return canExportSql(result, columnIndices, null);
    }

    /**
     * Performs the target-metadata check and, when a dialect parser is available, verifies that
     * every selected projection is a direct physical column rather than an expression.
     */
    public boolean canExportSql(StatementResult result, List<Integer> columnIndices, SqlDialect dialect) {
        if (result == null || result.mutationTarget() == null || columnIndices == null || columnIndices.isEmpty()) {
            return false;
        }
        ResultMutationTarget target = result.mutationTarget();
        String reason = target.reasonCode() == null ? "" : target.reasonCode().trim();
        if (!reason.isEmpty() && !"FOR_UPDATE_REQUIRED".equals(reason)
                && !"NO_SAFE_ROW_KEY".equals(reason) && !"NON_TRANSACTIONAL_TABLE".equals(reason)) return false;
        Map<Integer, ResultMutationTarget.Column> columns = targetColumns(target);
        for (Integer index : columnIndices) {
            ResultMutationTarget.Column column = index == null ? null : columns.get(index);
            if (column == null || column.name() == null || column.name().trim().isEmpty()
                    || column.quotedName() == null || column.quotedName().trim().isEmpty()) return false;
        }
        if (dialect != null) {
            final List<String> sourceNames;
            try { sourceNames = dialect.resultColumnNames(result.sql()); }
            catch (RuntimeException exception) { return false; }
            if (!sourceNames.isEmpty()) {
                if (sourceNames.size() != result.columns().size()) return false;
                for (Integer index : columnIndices) {
                    String sourceName = sourceNames.get(index);
                    ResultMutationTarget.Column targetColumn = columns.get(index);
                    if (sourceName == null || sourceName.trim().isEmpty()
                            || !sameIdentifier(sourceName, targetColumn.name())) return false;
                }
            }
        }
        return !tableName(target.qualifiedName()).isEmpty();
    }

    private void writeLoadedExcel(StatementResult result, OutputStream output,
                                  List<Integer> rowIndices, List<Integer> columnIndices) throws IOException {
        validateColumns(result.columns().size(), columnIndices);
        validateRows(result.rows().size(), rowIndices);
        if (columnIndices.size() > EXCEL_COLUMN_LIMIT) throw new IOException("结果字段数超过 Excel 限制");
        List<String> headers = new ArrayList<String>(columnIndices.size());
        for (Integer column : columnIndices) headers.add(result.columns().get(column));
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try {
            writeExcelRows(workbook, output, headers,
                    row -> valueAt(result.rows().get(row), columnIndices), rowIndices, columnIndices.size());
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private void writeLoadedCsv(StatementResult result, OutputStream output,
                                List<Integer> rowIndices, List<Integer> columnIndices) throws IOException {
        validateColumns(result.columns().size(), columnIndices);
        validateRows(result.rows().size(), rowIndices);
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(output, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setRecordSeparator(System.lineSeparator()).get())) {
            List<String> headers = new ArrayList<String>(columnIndices.size());
            for (Integer column : columnIndices) headers.add(result.columns().get(column));
            printer.printRecord(headers);
            for (Integer rowIndex : rowIndices) {
                List<String> values = new ArrayList<String>(columnIndices.size());
                List<String> row = result.rows().get(rowIndex);
                for (Integer column : columnIndices) {
                    String value = column < row.size() ? row.get(column) : null;
                    values.add(value == null ? CsvService.NULL_VALUE : value);
                }
                printer.printRecord(values);
            }
        }
    }

    private void writeFullExcel(DatabaseSession session, StatementResult source, OutputStream output)
            throws SQLException, IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (Statement statement = session.jdbcConnection().createStatement()) {
            statement.setFetchSize(QueryRunner.JDBC_FETCH_SIZE);
            if (!statement.execute(source.sql())) throw new SQLException("该语句没有返回结果集");
            try (ResultSet resultSet = statement.getResultSet()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int count = metadata.getColumnCount();
                if (count > EXCEL_COLUMN_LIMIT) throw new IOException("结果字段数超过 Excel 限制");
                List<String> headers = new ArrayList<String>(count);
                for (int index = 1; index <= count; index++) headers.add(metadata.getColumnLabel(index));
                writeExcelRows(workbook, output, headers, row -> {
                    List<String> values = new ArrayList<String>(count);
                    for (int index = 1; index <= count; index++) {
                        try { values.add(QueryRunner.displayValue(resultSet.getObject(index))); }
                        catch (SQLException exception) { throw new ExportSqlRuntimeException(exception); }
                    }
                    return values;
                }, resultSet, count);
            }
        } catch (ExportSqlRuntimeException exception) {
            throw exception.sqlException;
        } finally {
            workbook.dispose();
            workbook.close();
        }
    }

    private void writeExcelRows(SXSSFWorkbook workbook, OutputStream output, List<String> headers,
                                RowValues values, Iterable<Integer> rows, int columnCount) throws IOException {
        SheetState state = new SheetState(workbook, headers, columnCount);
        for (Integer rowIndex : rows) state.write(values.values(rowIndex));
        workbook.write(output);
    }

    private void writeExcelRows(SXSSFWorkbook workbook, OutputStream output, List<String> headers,
                                RowValues values, ResultSet resultSet, int columnCount)
            throws SQLException, IOException {
        SheetState state = new SheetState(workbook, headers, columnCount);
        while (resultSet.next()) state.write(values.values(0));
        workbook.write(output);
    }

    private void writeLoadedSql(StatementResult result, OutputStream output, List<Integer> rowIndices,
                                List<Integer> columnIndices, SqlDialect dialect) throws IOException {
        validateColumns(result.columns().size(), columnIndices);
        validateRows(result.rows().size(), rowIndices);
        if (!canExportSql(result, columnIndices, dialect)) throw new IOException("当前结果无法可靠映射到单一目标表");
        Map<Integer, ResultMutationTarget.Column> targetColumns = targetColumns(result.mutationTarget());
        List<ResultMutationTarget.Column> columns = new ArrayList<ResultMutationTarget.Column>();
        for (Integer index : columnIndices) columns.add(targetColumns.get(index));
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writeSqlRows(writer, result.mutationTarget(), columns, rowIndices,
                    row -> result.rows().get(row), dialect);
        }
    }

    private void writeFullSql(DatabaseSession session, StatementResult source, OutputStream output,
                              SqlDialect dialect) throws SQLException, IOException {
        List<Integer> indices = new ArrayList<Integer>();
        for (int index = 0; index < source.columns().size(); index++) indices.add(index);
        if (!canExportSql(source, indices, dialect)) throw new IOException("当前结果无法可靠映射到单一目标表");
        Map<Integer, ResultMutationTarget.Column> targetColumns = targetColumns(source.mutationTarget());
        List<ResultMutationTarget.Column> columns = new ArrayList<ResultMutationTarget.Column>();
        for (Integer index : indices) columns.add(targetColumns.get(index));
        try (Statement statement = session.jdbcConnection().createStatement();
             Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            statement.setFetchSize(QueryRunner.JDBC_FETCH_SIZE);
            if (!statement.execute(source.sql())) throw new SQLException("该语句没有返回结果集");
            try (ResultSet resultSet = statement.getResultSet()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                if (metadata.getColumnCount() != indices.size()) {
                    throw new IOException("重新执行后的结果字段数已变化，无法生成 SQL 文件");
                }
                for (int index = 0; index < indices.size(); index++) {
                    String label = metadata.getColumnLabel(index + 1);
                    if (!source.columns().get(index).equals(label)) {
                        throw new IOException("重新执行后的结果字段已变化，无法生成 SQL 文件");
                    }
                }
                while (resultSet.next()) {
                    List<String> row = new ArrayList<String>(indices.size());
                    for (int index = 1; index <= metadata.getColumnCount(); index++) {
                        row.add(QueryRunner.displayValue(resultSet.getObject(index)));
                    }
                    writeSqlRow(writer, source.mutationTarget(), columns, row, dialect);
                }
            }
        }
    }

    private void writeSqlRows(Writer writer, ResultMutationTarget target,
                              List<ResultMutationTarget.Column> columns, Iterable<Integer> rows,
                              RowValues values, SqlDialect dialect) throws IOException {
        for (Integer rowIndex : rows) writeSqlRow(writer, target, columns, values.values(rowIndex), dialect);
    }

    private void writeSqlRow(Writer writer, ResultMutationTarget target,
                             List<ResultMutationTarget.Column> columns, List<String> row,
                             SqlDialect dialect) throws IOException {
        StringBuilder sql = new StringBuilder("insert into ").append(identifier(dialect,
                unquoteIdentifier(tableName(target.qualifiedName())), tableName(target.qualifiedName()))).append('(');
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) sql.append(", ");
            ResultMutationTarget.Column column = columns.get(index);
            sql.append(identifier(dialect, column.name(), column.quotedName()));
        }
        sql.append(") values(");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) sql.append(", ");
            String value = index < row.size() ? row.get(index) : null;
            sql.append(sqlLiteral(dialect, value, columns.get(index).jdbcType()));
        }
        writer.write(sql.append(");\n").toString());
    }

    private static String sqlLiteral(SqlDialect dialect, String value, int jdbcType) {
        if (value == null) return "NULL";
        if ((jdbcType == Types.BINARY || jdbcType == Types.VARBINARY || jdbcType == Types.LONGVARBINARY
                || jdbcType == Types.BLOB) && value.matches("(?i)^0x[0-9a-f]+$")) {
            if (dialect.id().contains("oracle")) return "HEXTORAW('" + value.substring(2) + "')";
            return value;
        }
        if ((jdbcType == Types.TINYINT || jdbcType == Types.SMALLINT || jdbcType == Types.INTEGER
                || jdbcType == Types.BIGINT || jdbcType == Types.FLOAT || jdbcType == Types.REAL
                || jdbcType == Types.DOUBLE || jdbcType == Types.NUMERIC || jdbcType == Types.DECIMAL)
                && value.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) return value;
        if (jdbcType == Types.BOOLEAN || jdbcType == Types.BIT) {
            if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
                return dialect.id().contains("oracle") ? "1" : "TRUE";
            }
            if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
                return dialect.id().contains("oracle") ? "0" : "FALSE";
            }
        }
        if (dialect.id().contains("oracle") && jdbcType == Types.DATE
                && value.matches("\\d{4}-\\d{2}-\\d{2}")) return "DATE '" + value + "'";
        if (dialect.id().contains("oracle")
                && (jdbcType == Types.TIME || jdbcType == Types.TIMESTAMP || jdbcType == 2013 || jdbcType == 2014)
                && value.matches("\\d{4}-\\d{2}-\\d{2}[ T].*")) {
            return "TIMESTAMP '" + value.replace('T', ' ') + "'";
        }
        return dialect.sqlLiteral(value, jdbcType);
    }

    private static List<String> valueAt(List<String> row, List<Integer> indices) {
        List<String> values = new ArrayList<String>(indices.size());
        for (Integer index : indices) values.add(index == null || index >= row.size() ? null : row.get(index));
        return values;
    }

    private static Map<Integer, ResultMutationTarget.Column> targetColumns(ResultMutationTarget target) {
        Map<Integer, ResultMutationTarget.Column> result = new HashMap<Integer, ResultMutationTarget.Column>();
        for (ResultMutationTarget.Column column : target.columns()) result.put(column.resultIndex(), column);
        return result;
    }

    private static String tableName(String qualifiedName) {
        if (qualifiedName == null) return "";
        boolean single = false, doubleQuote = false, backtick = false, bracket = false;
        int start = 0;
        for (int index = 0; index < qualifiedName.length(); index++) {
            char value = qualifiedName.charAt(index);
            if (value == '\'' && !doubleQuote && !backtick && !bracket) single = !single;
            else if (value == '"' && !single && !backtick && !bracket) doubleQuote = !doubleQuote;
            else if (value == '`' && !single && !doubleQuote && !bracket) backtick = !backtick;
            else if (value == '[' && !single && !doubleQuote && !backtick) bracket = true;
            else if (value == ']' && bracket) bracket = false;
            else if (value == '.' && !single && !doubleQuote && !backtick && !bracket) start = index + 1;
        }
        return qualifiedName.substring(Math.min(start, qualifiedName.length())).trim();
    }

    private static String identifier(SqlDialect dialect, String logicalName, String quotedName) {
        String value = logicalName == null ? "" : logicalName.trim();
        String quoted = quotedName == null ? "" : quotedName.trim();
        if (value.matches("[A-Za-z_][A-Za-z0-9_$]*")
                && !dialect.keywords().contains(value.toUpperCase(Locale.ROOT))) {
            String rawQuoted = unquoteIdentifier(quoted);
            // Preserve explicitly quoted mixed-case identifiers whose spelling is significant.
            if (quoted.isEmpty() || rawQuoted.equals(value)
                    && ((rawQuoted.equals(rawQuoted.toLowerCase(Locale.ROOT))
                    && unquotedLowercaseEquivalent(dialect))
                    || (rawQuoted.equals(rawQuoted.toUpperCase(Locale.ROOT))
                    && foldsUppercaseIdentifiers(dialect)))) return value;
        }
        return quoted.isEmpty() ? dialect.quoteIdentifier(value) : quoted;
    }

    private static boolean foldsUppercaseIdentifiers(SqlDialect dialect) {
        String id = dialect == null || dialect.id() == null ? "" : dialect.id().toLowerCase(Locale.ROOT);
        return id.contains("mysql") || id.contains("oracle");
    }

    private static boolean unquotedLowercaseEquivalent(SqlDialect dialect) {
        String id = dialect == null || dialect.id() == null ? "" : dialect.id().toLowerCase(Locale.ROOT);
        return id.contains("mysql") || id.contains("postgres") || id.contains("sqlite");
    }

    private static String unquoteIdentifier(String value) {
        if (value == null || value.length() < 2) return value == null ? "" : value;
        char first = value.charAt(0), last = value.charAt(value.length() - 1);
        if ((first == '`' && last == '`') || (first == '"' && last == '"')) {
            return value.substring(1, value.length() - 1);
        }
        if (first == '[' && last == ']') return value.substring(1, value.length() - 1);
        return value;
    }

    private static boolean sameIdentifier(String left, String right) {
        return unquoteIdentifier(left == null ? "" : left.trim())
                .equalsIgnoreCase(unquoteIdentifier(right == null ? "" : right.trim()));
    }

    private static void validateRows(int size, List<Integer> rows) {
        if (rows == null) throw new IllegalArgumentException("导出行范围不能为空");
        HashSet<Integer> seen = new HashSet<Integer>();
        for (Integer index : rows) if (index == null || index < 0 || index >= size) {
            throw new IllegalArgumentException("导出行范围已过期");
        } else if (!seen.add(index)) {
            throw new IllegalArgumentException("导出行范围不能重复");
        }
    }

    private static void validateColumns(int size, List<Integer> columns) {
        if (columns == null || columns.isEmpty()) throw new IllegalArgumentException("至少选择一个导出字段");
        HashSet<Integer> seen = new HashSet<Integer>();
        for (Integer index : columns) if (index == null || index < 0 || index >= size) {
            throw new IllegalArgumentException("导出字段范围已过期");
        } else if (!seen.add(index)) {
            throw new IllegalArgumentException("导出字段范围不能重复");
        }
    }

    private interface RowValues {
        List<String> values(int rowIndex);
    }

    private static final class SheetState {
        private final SXSSFWorkbook workbook;
        private final List<String> headers;
        private final int columnCount;
        private final CellStyle textStyle;
        private Sheet sheet;
        private int dataRows;
        private int sheetNumber;

        private SheetState(SXSSFWorkbook workbook, List<String> headers, int columnCount) {
            this.workbook = workbook; this.headers = headers; this.columnCount = columnCount;
            this.textStyle = workbook.createCellStyle();
            this.textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
            createSheet();
        }

        private void createSheet() {
            int number = sheetNumber++;
            sheet = workbook.createSheet(number == 0 ? "结果" : "结果_" + (number + 1));
            Row header = sheet.createRow(0);
            for (int column = 0; column < columnCount; column++) writeCell(header, column,
                    headers.get(column));
            dataRows = 0;
        }

        private void write(List<String> values) {
            if (dataRows >= EXCEL_DATA_ROWS_PER_SHEET) createSheet();
            Row row = sheet.createRow(++dataRows);
            for (int column = 0; column < columnCount; column++) {
                writeCell(row, column, column < values.size() ? values.get(column) : null);
            }
        }

        private void writeCell(Row row, int column, String value) {
            Cell cell = row.createCell(column);
            cell.setCellStyle(textStyle);
            if (value != null) cell.setCellValue(value);
        }
    }

    private static final class ExportSqlRuntimeException extends RuntimeException {
        private final SQLException sqlException;
        private ExportSqlRuntimeException(SQLException exception) { super(exception); this.sqlException = exception; }
    }
}
