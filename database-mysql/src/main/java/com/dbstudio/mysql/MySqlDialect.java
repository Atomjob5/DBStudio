package com.dbstudio.mysql;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class MySqlDialect implements SqlDialect {
    private static final Pattern FIRST_WORD = Pattern.compile("(?is)^\\s*(?:/\\*.*?\\*/\\s*)*(?:--[^\\r\\n]*[\\r\\n]+\\s*)*([a-z]+)");
    private static final Set<String> KEYWORDS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "DATABASE", "SCHEMA",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING",
            "LIMIT", "OFFSET", "UNION", "ALL", "DISTINCT", "AS", "AND", "OR", "NOT", "NULL",
            "IS", "IN", "EXISTS", "BETWEEN", "LIKE", "CASE", "WHEN", "THEN", "ELSE", "END",
            "WITH", "RECURSIVE", "OVER", "PARTITION", "ROWS", "RANGE", "PROCEDURE", "FUNCTION",
            "TRIGGER", "BEGIN", "DECLARE", "IF", "ELSEIF", "WHILE", "LOOP", "RETURN", "CALL",
            "COMMIT", "ROLLBACK", "START", "TRANSACTION", "EXPLAIN", "SHOW", "DESCRIBE",
            "COUNT", "SUM", "AVG", "MIN", "MAX", "NOW", "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "COALESCE", "IFNULL", "CONCAT", "JSON_EXTRACT", "ROW_NUMBER", "RANK")));

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return MySqlMetadataAdapter.quote(identifier);
    }

    @Override
    public Set<String> keywords() {
        return KEYWORDS;
    }

    @Override
    public List<SqlStatement> split(String script) {
        if (script == null || script.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<SqlStatement> statements = new ArrayList<SqlStatement>();
        String delimiter = ";";
        State state = State.NORMAL;
        int start = 0;
        int index = 0;
        boolean lineStart = true;

        while (index < script.length()) {
            if (state == State.NORMAL && lineStart) {
                DelimiterDirective directive = delimiterDirective(script, index);
                if (directive != null && onlyWhitespace(script, start, index)) {
                    delimiter = directive.delimiter();
                    index = directive.nextOffset();
                    start = index;
                    lineStart = true;
                    continue;
                }
            }

            char current = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';

            switch (state) {
                case NORMAL:
                    if (current == '\'' ) {
                        state = State.SINGLE_QUOTE;
                    } else if (current == '"') {
                        state = State.DOUBLE_QUOTE;
                    } else if (current == '`') {
                        state = State.BACKTICK;
                    } else if (current == '#') {
                        state = State.LINE_COMMENT;
                    } else if (current == '-' && next == '-' && isDashComment(script, index + 2)) {
                        state = State.LINE_COMMENT;
                        index++;
                    } else if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        index++;
                    } else if (script.startsWith(delimiter, index)) {
                        addStatement(statements, script, start, index);
                        index += delimiter.length();
                        start = index;
                        lineStart = false;
                        continue;
                    }
                    break;
                case SINGLE_QUOTE:
                    if (current == '\\') {
                        index++;
                    } else if (current == '\'' && next == '\'') {
                        index++;
                    } else if (current == '\'') {
                        state = State.NORMAL;
                    }
                    break;
                case DOUBLE_QUOTE:
                    if (current == '\\') {
                        index++;
                    } else if (current == '"' && next == '"') {
                        index++;
                    } else if (current == '"') {
                        state = State.NORMAL;
                    }
                    break;
                case BACKTICK:
                    if (current == '`' && next == '`') {
                        index++;
                    } else if (current == '`') {
                        state = State.NORMAL;
                    }
                    break;
                case LINE_COMMENT:
                    if (current == '\n' || current == '\r') {
                        state = State.NORMAL;
                    }
                    break;
                case BLOCK_COMMENT:
                    if (current == '*' && next == '/') {
                        state = State.NORMAL;
                        index++;
                    }
                    break;
            }

            lineStart = current == '\n' || current == '\r';
            index++;
        }
        addStatement(statements, script, start, script.length());
        return Collections.unmodifiableList(new ArrayList<SqlStatement>(statements));
    }

    @Override
    public Optional<SqlStatement> currentStatement(String script, int cursorOffset) {
        List<SqlStatement> statements = split(script);
        if (statements.isEmpty()) {
            return Optional.empty();
        }
        final int cursor = Math.max(0, Math.min(cursorOffset, script == null ? 0 : script.length()));
        for (SqlStatement statement : statements) {
            if (cursor >= statement.startOffset() && cursor <= statement.endOffset()) {
                return Optional.of(statement);
            }
        }
        return statements.stream()
                .min((left, right) -> Integer.compare(
                        distance(cursor, left),
                        distance(cursor, right)));
    }

    @Override
    public StatementType classify(String sql) {
        if (sql == null) {
            return StatementType.OTHER;
        }
        java.util.regex.Matcher matcher = FIRST_WORD.matcher(sql);
        if (!matcher.find()) {
            return StatementType.OTHER;
        }
        String keyword = matcher.group(1).toUpperCase(Locale.ROOT);
        if (Arrays.asList("SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "WITH").contains(keyword)) return StatementType.QUERY;
        if (Arrays.asList("INSERT", "REPLACE").contains(keyword)) return StatementType.INSERT;
        if ("UPDATE".equals(keyword)) return StatementType.UPDATE;
        if ("DELETE".equals(keyword)) return StatementType.DELETE;
        if (Arrays.asList("CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME").contains(keyword)) return StatementType.DDL;
        if (Arrays.asList("COMMIT", "ROLLBACK", "START", "BEGIN", "SAVEPOINT", "RELEASE").contains(keyword)) return StatementType.TRANSACTION;
        return StatementType.OTHER;
    }

    @Override
    public String format(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return sql == null ? "" : sql;
        }
        try {
            return SQLUtils.format(sql, DbType.mysql);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("无法格式化当前 SQL：" + exception.getMessage(), exception);
        }
    }

    @Override
    public List<String> resultColumnNames(String sql) {
        try {
            SQLStatement statement = SQLUtils.parseSingleMysqlStatement(sql);
            if (!(statement instanceof SQLSelectStatement)) return Collections.emptyList();
            SQLSelectQueryBlock block = ((SQLSelectStatement) statement).getSelect().getQueryBlock();
            if (block == null || block.selectItemHasAllColumn()) return Collections.emptyList();
            List<String> names = new ArrayList<String>(block.getSelectList().size());
            for (SQLSelectItem item : block.getSelectList()) names.add(sourceColumnName(item.getExpr()));
            return Collections.unmodifiableList(names);
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
    }

    private static String sourceColumnName(SQLExpr expression) {
        if (expression instanceof SQLIdentifierExpr) return ((SQLIdentifierExpr) expression).getName();
        if (expression instanceof SQLPropertyExpr) return ((SQLPropertyExpr) expression).getName();
        return "";
    }

    private void addStatement(List<SqlStatement> statements, String script, int rawStart, int rawEnd) {
        int start = rawStart;
        int end = rawEnd;
        while (start < end && Character.isWhitespace(script.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(script.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            String text = script.substring(start, end);
            statements.add(new SqlStatement(text, start, end, classify(text)));
        }
    }

    private static DelimiterDirective delimiterDirective(String script, int offset) {
        int cursor = offset;
        while (cursor < script.length() && (script.charAt(cursor) == ' ' || script.charAt(cursor) == '\t')) {
            cursor++;
        }
        String keyword = "DELIMITER";
        if (cursor + keyword.length() > script.length()
                || !script.regionMatches(true, cursor, keyword, 0, keyword.length())) {
            return null;
        }
        cursor += keyword.length();
        if (cursor >= script.length() || !Character.isWhitespace(script.charAt(cursor))) {
            return null;
        }
        while (cursor < script.length() && (script.charAt(cursor) == ' ' || script.charAt(cursor) == '\t')) {
            cursor++;
        }
        int valueStart = cursor;
        while (cursor < script.length() && script.charAt(cursor) != '\r' && script.charAt(cursor) != '\n') {
            cursor++;
        }
        String delimiter = script.substring(valueStart, cursor).trim();
        if (delimiter.isEmpty()) {
            return null;
        }
        while (cursor < script.length() && (script.charAt(cursor) == '\r' || script.charAt(cursor) == '\n')) {
            cursor++;
        }
        return new DelimiterDirective(delimiter, cursor);
    }

    private static boolean onlyWhitespace(String script, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(script.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDashComment(String script, int offset) {
        return offset >= script.length() || Character.isWhitespace(script.charAt(offset));
    }

    private static int distance(int cursor, SqlStatement statement) {
        if (cursor < statement.startOffset()) {
            return statement.startOffset() - cursor;
        }
        if (cursor > statement.endOffset()) {
            return cursor - statement.endOffset();
        }
        return 0;
    }

    private enum State {
        NORMAL,
        SINGLE_QUOTE,
        DOUBLE_QUOTE,
        BACKTICK,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    private static final class DelimiterDirective {
        private final String delimiter;
        private final int nextOffset;

        private DelimiterDirective(String delimiter, int nextOffset) {
            this.delimiter = delimiter;
            this.nextOffset = nextOffset;
        }

        private String delimiter() { return delimiter; }
        private int nextOffset() { return nextOffset; }
    }
}
