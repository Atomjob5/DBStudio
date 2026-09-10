package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared offset and error-message handling for parser-backed dialect diagnostics. */
public final class SqlSyntaxDiagnosticSupport {
    private static final Pattern LINE_COLUMN = Pattern.compile(
            "(?i)\\bline\\s+(\\d+)\\s*,?\\s*column\\s+(\\d+)");
    private static final Pattern POSITION = Pattern.compile("(?i)\\bpos\\s+(\\d+)");
    private static final Pattern SOURCE_SQL = Pattern.compile("(?is)\\Rsource\\s+sql\\s+is\\s*:.*$");
    private static final Pattern LOCATION_SUFFIX = Pattern.compile(
            "(?is),?\\s*pos\\s+\\d+\\s*,?\\s*line\\s+\\d+\\s*,?\\s*column\\s+\\d+.*$");
    private static final int MAXIMUM_MESSAGE_CHARACTERS = 300;

    private SqlSyntaxDiagnosticSupport() { }

    public interface StatementParser {
        void parse(String sql);
    }

    public static List<SqlDiagnostic> analyze(String script, List<SqlStatement> statements,
                                               StatementParser parser) {
        if (script == null || script.trim().isEmpty() || statements == null || statements.isEmpty()) {
            return Collections.emptyList();
        }
        List<SqlDiagnostic> result = new ArrayList<SqlDiagnostic>();
        for (SqlStatement statement : statements) {
            try {
                parser.parse(statement.text());
            } catch (RuntimeException exception) {
                if (!isSyntaxFailure(exception)) continue;
                int localOffset = diagnosticOffset(statement.text(), exception.getMessage());
                int start = Math.max(statement.startOffset(), Math.min(statement.endOffset(),
                        statement.startOffset() + localOffset));
                int end = start < statement.endOffset() ? start + 1 : start;
                if (end == start && statement.endOffset() > statement.startOffset()) {
                    start = statement.endOffset() - 1;
                    end = statement.endOffset();
                }
                result.add(new SqlDiagnostic("SQL_SYNTAX_ERROR", SqlDiagnostic.CATEGORY_SYNTAX,
                        SqlDiagnostic.SEVERITY_ERROR, diagnosticMessage(exception), start, end));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Parser wrappers occasionally throw an unchecked exception for an unavailable
     * dialect feature or an internal parser bug. Those failures must not become a
     * red syntax marker in the editor. Keep the compatibility heuristic narrow:
     * known parser exception types or messages that explicitly describe syntax.
     */
    private static boolean isSyntaxFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String type = current.getClass().getName().toLowerCase(Locale.ROOT);
            if (type.contains("parserexception") || type.contains("parseexception")
                    || type.contains("syntaxerror")) return true;
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).matches(
                    "(?s).*\\b(?:syntax\\s+(?:error|exception|near|at|invalid)|parse\\s+(?:error|failure|failed|unexpected)|unexpected(?: token)?|invalid token|unclosed|eof|parenthes|line\\s+\\d+|pos\\s+\\d+).*")) {
                return true;
            }
        }
        return false;
    }

    private static int diagnosticOffset(String sql, String message) {
        String value = message == null ? "" : message;
        Matcher location = LINE_COLUMN.matcher(value);
        if (location.find()) {
            int line = positive(location.group(1), 1);
            int column = positive(location.group(2), 1);
            int offset = offsetAt(sql, line, column);
            if (offset >= 0) return offset;
        }
        Matcher position = POSITION.matcher(value);
        if (position.find()) {
            int parsed = positive(position.group(1), 0);
            if (parsed >= 0) return Math.max(0, Math.min(sql.length(), parsed));
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("eof") ? sql.length() : firstContentOffset(sql);
    }

    private static int offsetAt(String sql, int oneBasedLine, int oneBasedColumn) {
        if (oneBasedLine < 1 || oneBasedColumn < 1) return -1;
        int line = 1;
        int offset = 0;
        while (line < oneBasedLine && offset < sql.length()) {
            char current = sql.charAt(offset++);
            if (current == '\r') {
                if (offset < sql.length() && sql.charAt(offset) == '\n') offset++;
                line++;
            } else if (current == '\n') {
                line++;
            }
        }
        if (line != oneBasedLine) return -1;
        return Math.max(0, Math.min(sql.length(), offset + oneBasedColumn - 1));
    }

    private static int firstContentOffset(String sql) {
        int offset = 0;
        while (offset < sql.length() && Character.isWhitespace(sql.charAt(offset))) offset++;
        return offset;
    }

    private static int positive(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static String diagnosticMessage(RuntimeException exception) {
        String detail = exception.getMessage();
        if (detail == null || detail.trim().isEmpty()) return "SQL 语法错误";
        detail = SOURCE_SQL.matcher(detail).replaceFirst("");
        detail = LOCATION_SUFFIX.matcher(detail).replaceFirst("").trim();
        detail = detail.replaceAll("\\s+", " ");
        if (detail.length() > MAXIMUM_MESSAGE_CHARACTERS) {
            detail = detail.substring(0, MAXIMUM_MESSAGE_CHARACTERS - 1) + "…";
        }
        return "SQL 语法错误：" + detail;
    }
}
