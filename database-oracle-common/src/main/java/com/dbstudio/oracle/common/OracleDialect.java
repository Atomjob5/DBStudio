package com.dbstudio.oracle.common;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLDbLinkExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.ResultMutationSource;
import com.dbstudio.spi.ResultEditPlan;
import com.dbstudio.spi.SqlCommentSupport;
import com.dbstudio.spi.SqlDiagnostic;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.SqlDmlRiskAnalyzer;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.SqlSyntaxDiagnosticSupport;
import com.dbstudio.spi.SqlTextCompactor;
import com.dbstudio.spi.StatementType;
import com.dbstudio.spi.TransactionEffect;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OracleDialect implements SqlDialect {
    private static final Pattern FIRST_WORD = Pattern.compile(
            "(?is)^\\s*(?:/\\*.*?\\*/\\s*)*(?:--[^\\r\\n]*(?:[\\r\\n]+|$)\\s*)*([a-z]+)");
    private static final Pattern PLSQL_PREFIX = Pattern.compile(
            "(?is)^\\s*(?:DECLARE\\b|BEGIN\\b|CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION|PACKAGE(?:\\s+BODY)?|TRIGGER|TYPE(?:\\s+BODY)?)\\b)");
    private static final Set<String> KEYWORDS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "MERGE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "SEQUENCE", "SYNONYM",
            "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING",
            "FETCH", "FIRST", "ROWS", "ONLY", "OFFSET", "UNION", "ALL", "DISTINCT", "AS", "AND", "OR",
            "NOT", "NULL", "IS", "IN", "EXISTS", "BETWEEN", "LIKE", "CASE", "WHEN", "THEN", "ELSE", "END",
            "WITH", "OVER", "PARTITION", "RANGE", "PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER", "TYPE",
            "BEGIN", "DECLARE", "EXCEPTION", "LOOP", "RETURN", "CALL", "COMMIT", "ROLLBACK", "SAVEPOINT",
            "EXPLAIN", "PLAN", "FOR", "COUNT", "SUM", "AVG", "MIN", "MAX", "SYSDATE", "SYSTIMESTAMP",
            "CURRENT_DATE", "CURRENT_TIMESTAMP", "NVL", "COALESCE", "DECODE", "ROW_NUMBER", "RANK")));

    @Override public String id() { return "oracle"; }
    @Override public String quoteIdentifier(String identifier) { return OracleSessionSupport.quoteIdentifier(identifier); }
    @Override public Set<String> keywords() { return KEYWORDS; }

    @Override public List<SqlStatement> split(String script) {
        if (script == null || script.trim().isEmpty()) return Collections.emptyList();
        List<SqlStatement> result = new ArrayList<SqlStatement>();
        State state = State.NORMAL;
        char alternativeQuoteClosing = '\0';
        int start = 0;
        int lineStart = 0;
        for (int index = 0; index < script.length(); index++) {
            char current = script.charAt(index);
            char next = index + 1 < script.length() ? script.charAt(index + 1) : '\0';
            if (state == State.NORMAL && index == lineStart) {
                int lineEnd = lineEnd(script, index);
                if ("/".equals(script.substring(index, lineEnd).trim())
                        && isPlSql(script.substring(start, index))) {
                    add(result, script, start, index);
                    start = lineEnd;
                    while (start < script.length() && (script.charAt(start) == '\r' || script.charAt(start) == '\n')) start++;
                    index = start - 1;
                    lineStart = start;
                    continue;
                }
            }
            switch (state) {
                case NORMAL:
                    if ((current == 'q' || current == 'Q') && next == '\'' && index + 2 < script.length()) {
                        alternativeQuoteClosing = pairedQuoteDelimiter(script.charAt(index + 2));
                        state = State.ALTERNATIVE_QUOTE;
                        index += 2;
                    }
                    else if (current == '\'') state = State.SINGLE_QUOTE;
                    else if (current == '"') state = State.DOUBLE_QUOTE;
                    else if (current == '-' && next == '-') { state = State.LINE_COMMENT; index++; }
                    else if (current == '/' && next == '*') { state = State.BLOCK_COMMENT; index++; }
                    else if (current == ';' && !isPlSql(script.substring(start, index + 1))) {
                        add(result, script, start, index);
                        start = index + 1;
                    }
                    break;
                case SINGLE_QUOTE:
                    if (current == '\'' && next == '\'') index++;
                    else if (current == '\'') state = State.NORMAL;
                    break;
                case DOUBLE_QUOTE:
                    if (current == '"' && next == '"') index++;
                    else if (current == '"') state = State.NORMAL;
                    break;
                case ALTERNATIVE_QUOTE:
                    if (current == alternativeQuoteClosing && next == '\'') { state = State.NORMAL; index++; }
                    break;
                case LINE_COMMENT:
                    if (current == '\r' || current == '\n') state = State.NORMAL;
                    break;
                case BLOCK_COMMENT:
                    if (current == '*' && next == '/') { state = State.NORMAL; index++; }
                    break;
                default: break;
            }
            if (current == '\n') lineStart = index + 1;
            else if (current == '\r') lineStart = next == '\n' ? index + 2 : index + 1;
        }
        add(result, script, start, script.length());
        return Collections.unmodifiableList(result);
    }

    @Override public Optional<SqlStatement> currentStatement(String script, int cursorOffset) {
        SqlCommentSupport.CursorLine line = SqlCommentSupport.cursorLine(
                script, cursorOffset, SqlCommentSupport.Dialect.ORACLE);
        List<SqlStatement> statements = split(script);
        return SqlCommentSupport.statementOnCursorLine(
                script, SqlCommentSupport.Dialect.ORACLE, statements, cursorOffset, line);
    }

    @Override public StatementType classify(String sql) {
        Matcher matcher = FIRST_WORD.matcher(sql == null ? "" : sql);
        if (!matcher.find()) return StatementType.OTHER;
        String keyword = matcher.group(1).toUpperCase(Locale.ROOT);
        if (Arrays.asList("SELECT", "WITH", "EXPLAIN").contains(keyword)) return StatementType.QUERY;
        if ("INSERT".equals(keyword)) return StatementType.INSERT;
        if (Arrays.asList("UPDATE", "MERGE").contains(keyword)) return StatementType.UPDATE;
        if ("DELETE".equals(keyword)) return StatementType.DELETE;
        if (Arrays.asList("CREATE", "ALTER", "DROP", "TRUNCATE", "RENAME", "GRANT", "REVOKE").contains(keyword)) return StatementType.DDL;
        if (Arrays.asList("COMMIT", "ROLLBACK", "SAVEPOINT", "SET").contains(keyword)) return StatementType.TRANSACTION;
        return StatementType.OTHER;
    }

    @Override public boolean requiresWhereClauseConfirmation(SqlStatement statement) {
        if (statement == null || statement.text() == null || statement.text().trim().isEmpty()) return false;
        try {
            SQLStatement parsed = SQLUtils.parseSingleStatement(statement.text(), DbType.oracle);
            if (parsed instanceof SQLUpdateStatement) {
                return ((SQLUpdateStatement) parsed).getWhere() == null
                        && SqlDmlRiskAnalyzer.requiresWhereClauseConfirmation(statement.text());
            }
            if (parsed instanceof SQLDeleteStatement) {
                return ((SQLDeleteStatement) parsed).getWhere() == null
                        && SqlDmlRiskAnalyzer.requiresWhereClauseConfirmation(statement.text());
            }
            return SqlDmlRiskAnalyzer.requiresWhereClauseConfirmation(statement.text());
        } catch (RuntimeException ignored) {
            return SqlDmlRiskAnalyzer.requiresWhereClauseConfirmation(statement.text());
        }
    }

    @Override public String format(String sql) {
        if (sql == null || sql.trim().isEmpty()) return sql == null ? "" : sql;
        try { return SQLUtils.format(sql, DbType.oracle); }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException("无法格式化当前 Oracle SQL：" + exception.getMessage(), exception);
        }
    }

    @Override public List<SqlDiagnostic> syntaxDiagnostics(String script) {
        return SqlSyntaxDiagnosticSupport.analyze(script, split(script), value ->
                SQLUtils.parseSingleStatement(maskAlternativeQuotedLiterals(value), DbType.oracle));
    }

    @Override public String compact(String sql) {
        if (sql == null || sql.trim().isEmpty()) return sql == null ? "" : sql;
        try {
            return SqlTextCompactor.preserveComments(
                    sql, SQLUtils.format(sql, DbType.oracle, new SQLUtils.FormatOption(true, false)));
        }
        catch (RuntimeException exception) {
            throw new IllegalArgumentException("无法压缩当前 Oracle SQL：" + exception.getMessage(), exception);
        }
    }

    @Override public String qualifiedName(String catalog, String schema, String name) {
        return schema == null || schema.trim().isEmpty() ? quoteIdentifier(name)
                : quoteIdentifier(schema) + "." + quoteIdentifier(name);
    }

    @Override public String previewQuery(DatabaseObject object, int maxRows) {
        return "SELECT *\nFROM " + qualifiedName(object.catalog(), object.schema(), object.name())
                + "\nFETCH FIRST " + Math.max(1, maxRows) + " ROWS ONLY;";
    }

    @Override public TransactionEffect transactionEffect(SqlStatement statement, boolean producedResultSet) {
        TransactionEffect base = SqlDialect.super.transactionEffect(statement, producedResultSet);
        if (base != TransactionEffect.NONE || statement.type() != StatementType.QUERY) return base;
        return statement.text().toUpperCase(Locale.ROOT).matches("(?s).*\\bFOR\\s+UPDATE\\b.*")
                ? TransactionEffect.DIRTY : TransactionEffect.NONE;
    }

    @Override public String sqlLiteral(String value, int jdbcType) {
        if (value == null) return "NULL";
        if ((jdbcType == Types.BINARY || jdbcType == Types.VARBINARY || jdbcType == Types.LONGVARBINARY
                || jdbcType == Types.BLOB) && value.matches("(?i)0x[0-9a-f]+")) {
            return "HEXTORAW('" + value.substring(2) + "')";
        }
        if (jdbcType == Types.DATE && value.matches("\\d{4}-\\d{2}-\\d{2}")) return "DATE '" + value + "'";
        if ((jdbcType == Types.TIMESTAMP || jdbcType == Types.TIMESTAMP_WITH_TIMEZONE)
                && value.matches("\\d{4}-\\d{2}-\\d{2}[ T].*")) {
            return "TIMESTAMP '" + value.replace('T', ' ') + "'";
        }
        if (jdbcType == Types.BOOLEAN || jdbcType == Types.BIT) {
            if ("true".equalsIgnoreCase(value)) return "1";
            if ("false".equalsIgnoreCase(value)) return "0";
        }
        return SqlDialect.super.sqlLiteral(value, jdbcType);
    }

    @Override public List<String> resultColumnNames(String sql) {
        try {
            SQLSelectQueryBlock block = queryBlock(sql);
            if (block == null || block.selectItemHasAllColumn()) return Collections.emptyList();
            List<String> names = new ArrayList<String>();
            for (SQLSelectItem item : block.getSelectList()) names.add(sourceColumnName(item.getExpr()));
            return Collections.unmodifiableList(names);
        } catch (RuntimeException ignored) { return Collections.emptyList(); }
    }

    @Override public Optional<ResultMutationSource> resultMutationSource(String sql) {
        try {
            SQLStatement parsed = SQLUtils.parseSingleStatement(sql, DbType.oracle);
            if (!(parsed instanceof SQLSelectStatement)) return Optional.empty();
            SQLSelectStatement select = (SQLSelectStatement) parsed;
            if (select.getSelect().getWithSubQuery() != null) return Optional.empty();
            SQLSelectQueryBlock block = select.getSelect().getQueryBlock();
            if (block == null || !(block.getFrom() instanceof SQLExprTableSource)) return Optional.empty();
            SQLExprTableSource source = (SQLExprTableSource) block.getFrom();
            String table = normalizeOracleIdentifier(source.getTableName());
            String schema = normalizeOracleIdentifier(source.getSchema());
            return table.isEmpty() ? Optional.<ResultMutationSource>empty()
                    : Optional.of(new ResultMutationSource("", schema, table,
                            normalize(source.getAlias()), block.isForUpdate()));
        } catch (RuntimeException ignored) { return Optional.empty(); }
    }

    @Override public ResultEditPlan resultEditPlan(String sql) {
        try {
            SQLStatement parsed = SQLUtils.parseSingleStatement(sql, DbType.oracle);
            if (!(parsed instanceof SQLSelectStatement)) return ResultEditPlan.readOnly(
                    "NOT_QUERY", "只有查询结果支持编辑");
            SQLSelectStatement select = (SQLSelectStatement) parsed;
            if (select.getSelect().getWithSubQuery() != null) return ResultEditPlan.readOnly(
                    "CTE_NOT_SUPPORTED", "CTE 查询结果暂不支持编辑");
            SQLSelectQueryBlock block = select.getSelect().getQueryBlock();
            if (block == null) return ResultEditPlan.readOnly(
                    "SET_QUERY_NOT_SUPPORTED", "UNION 等集合查询结果暂不支持编辑");
            if (block.getGroupBy() != null || block.isDistinct() || hasAggregate(block)) {
                return ResultEditPlan.readOnly("AGGREGATE_NOT_SUPPORTED", "聚合或去重查询结果暂不支持编辑");
            }
            if (!(block.getFrom() instanceof SQLExprTableSource)) return ResultEditPlan.readOnly(
                    "JOIN_NOT_SUPPORTED", "联表或派生表查询结果暂不支持编辑");
            SQLExprTableSource table = (SQLExprTableSource) block.getFrom();
            if (table.getExpr() instanceof SQLDbLinkExpr || table.toString().contains("@")) {
                return ResultEditPlan.readOnly("DATABASE_LINK_NOT_SUPPORTED", "数据库链路查询结果暂不支持编辑");
            }
            Optional<ResultMutationSource> source = resultMutationSource(sql);
            if (!source.isPresent()) return ResultEditPlan.readOnly(
                    "UNSUPPORTED_QUERY_SHAPE", "当前查询形态无法安全定位单一基表");
            return source.get().editableForUpdate() ? ResultEditPlan.editable(source.get())
                    : ResultEditPlan.readOnly(source.get(), "FOR_UPDATE_REQUIRED", "需要显式执行单表 FOR UPDATE 查询");
        } catch (RuntimeException exception) {
            return ResultEditPlan.readOnly("SQL_PARSE_FAILED", "SQL 无法安全解析，结果保持只读");
        }
    }

    private static boolean hasAggregate(SQLSelectQueryBlock block) {
        for (SQLSelectItem item : block.getSelectList()) if (item.getExpr() instanceof SQLAggregateExpr) return true;
        return false;
    }

    @Override public String appendResultLocatorColumns(String sql, List<String> expressions, List<String> aliases) {
        if (expressions == null || expressions.isEmpty() || expressions.size() != aliases.size()) return sql;
        SQLStatement statement = SQLUtils.parseSingleStatement(sql, DbType.oracle);
        if (!(statement instanceof SQLSelectStatement)) return sql;
        SQLSelectQueryBlock block = ((SQLSelectStatement) statement).getSelect().getQueryBlock();
        if (block == null) return sql;
        qualifyBareWildcards(block);
        for (int index = 0; index < expressions.size(); index++) {
            block.addSelectItem(SQLUtils.toSQLExpr(expressions.get(index), DbType.oracle), aliases.get(index));
        }
        return SQLUtils.toSQLString(statement, DbType.oracle);
    }

    private static void qualifyBareWildcards(SQLSelectQueryBlock block) {
        if (!(block.getFrom() instanceof SQLExprTableSource)) return;
        SQLExprTableSource source = (SQLExprTableSource) block.getFrom();
        String alias = source.getAlias();
        String qualifier = alias == null || alias.trim().isEmpty()
                ? SQLUtils.toSQLString(source.getExpr(), DbType.oracle) : alias;
        for (SQLSelectItem item : block.getSelectList()) {
            if (item.getExpr() instanceof SQLAllColumnExpr) {
                item.setExpr(SQLUtils.toSQLExpr(qualifier + ".*", DbType.oracle));
            }
        }
    }

    @Override public String resultMutationQualifier(String sql, ResultMutationSource mutationSource) {
        try {
            SQLSelectQueryBlock block = queryBlock(sql);
            if (block == null || !(block.getFrom() instanceof SQLExprTableSource)) {
                return SqlDialect.super.resultMutationQualifier(sql, mutationSource);
            }
            SQLExprTableSource source = (SQLExprTableSource) block.getFrom();
            String alias = source.getAlias();
            return alias == null || alias.trim().isEmpty()
                    ? SQLUtils.toSQLString(source.getExpr(), DbType.oracle) : alias;
        } catch (RuntimeException ignored) {
            return SqlDialect.super.resultMutationQualifier(sql, mutationSource);
        }
    }

    @Override public String appendResultLocatorPredicate(String sql, List<String> predicates) {
        if (predicates == null || predicates.isEmpty()) return "";
        SQLStatement statement = SQLUtils.parseSingleStatement(sql, DbType.oracle);
        if (!(statement instanceof SQLSelectStatement)) return "";
        SQLSelectQueryBlock block = ((SQLSelectStatement) statement).getSelect().getQueryBlock();
        if (block == null) return "";
        block.addCondition(SQLUtils.toSQLExpr(String.join(" AND ", predicates), DbType.oracle));
        block.setLimit(null);
        return SQLUtils.toSQLString(statement, DbType.oracle);
    }

    private static SQLSelectQueryBlock queryBlock(String sql) {
        SQLStatement parsed = SQLUtils.parseSingleStatement(sql, DbType.oracle);
        return parsed instanceof SQLSelectStatement ? ((SQLSelectStatement) parsed).getSelect().getQueryBlock() : null;
    }
    private static String sourceColumnName(SQLExpr expression) {
        if (expression instanceof SQLIdentifierExpr) return ((SQLIdentifierExpr) expression).getName();
        if (expression instanceof SQLPropertyExpr) return ((SQLPropertyExpr) expression).getName();
        return "";
    }
    private static String normalize(String value) {
        if (value == null) return "";
        String result = value.trim();
        return result.length() > 1 && result.startsWith("\"") && result.endsWith("\"")
                ? result.substring(1, result.length() - 1).replace("\"\"", "\"") : result;
    }
    private static String normalizeOracleIdentifier(String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.length() > 1 && result.startsWith("\"") && result.endsWith("\"")) {
            return result.substring(1, result.length() - 1).replace("\"\"", "\"");
        }
        return result.toUpperCase(Locale.ROOT);
    }
    private void add(List<SqlStatement> result, String script, int rawStart, int rawEnd) {
        int start = rawStart, end = rawEnd;
        while (start < end && Character.isWhitespace(script.charAt(start))) start++;
        while (end > start && Character.isWhitespace(script.charAt(end - 1))) end--;
        if (start < end) {
            String text = script.substring(start, end);
            if (SqlCommentSupport.hasExecutableContent(text, SqlCommentSupport.Dialect.ORACLE)) {
                result.add(new SqlStatement(text, start, end, classify(text)));
            }
        }
    }
    private static boolean isPlSql(String value) { return PLSQL_PREFIX.matcher(value).find(); }
    private static int lineEnd(String script, int start) {
        int end = start;
        while (end < script.length() && script.charAt(end) != '\r' && script.charAt(end) != '\n') end++;
        return end;
    }
    /** Druid rejects apostrophes inside some valid q-quoted literals; mask them without moving offsets. */
    private static String maskAlternativeQuotedLiterals(String sql) {
        char[] masked = sql.toCharArray();
        State state = State.NORMAL;
        for (int index = 0; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (state == State.NORMAL) {
                if ((current == 'q' || current == 'Q') && next == '\'' && index + 2 < sql.length()) {
                    char closing = pairedQuoteDelimiter(sql.charAt(index + 2));
                    int end = alternativeQuoteEnd(sql, index + 3, closing);
                    if (end < 0) continue;
                    for (int cursor = index; cursor < end; cursor++) {
                        if (masked[cursor] != '\r' && masked[cursor] != '\n') masked[cursor] = ' ';
                    }
                    masked[index] = '\'';
                    masked[end - 1] = '\'';
                    index = end - 1;
                } else if (current == '\'') state = State.SINGLE_QUOTE;
                else if (current == '"') state = State.DOUBLE_QUOTE;
                else if (current == '-' && next == '-') { state = State.LINE_COMMENT; index++; }
                else if (current == '/' && next == '*') { state = State.BLOCK_COMMENT; index++; }
            } else if (state == State.SINGLE_QUOTE) {
                if (current == '\'' && next == '\'') index++;
                else if (current == '\'') state = State.NORMAL;
            } else if (state == State.DOUBLE_QUOTE) {
                if (current == '"' && next == '"') index++;
                else if (current == '"') state = State.NORMAL;
            } else if (state == State.LINE_COMMENT) {
                if (current == '\r' || current == '\n') state = State.NORMAL;
            } else if (state == State.BLOCK_COMMENT && current == '*' && next == '/') {
                state = State.NORMAL;
                index++;
            }
        }
        return new String(masked);
    }
    private static int alternativeQuoteEnd(String sql, int start, char closing) {
        for (int index = start; index + 1 < sql.length(); index++) {
            if (sql.charAt(index) == closing && sql.charAt(index + 1) == '\'') return index + 2;
        }
        return -1;
    }
    private static char pairedQuoteDelimiter(char opening) {
        return opening == '[' ? ']' : opening == '{' ? '}' : opening == '(' ? ')'
                : opening == '<' ? '>' : opening;
    }
    private enum State { NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, ALTERNATIVE_QUOTE, LINE_COMMENT, BLOCK_COMMENT }
}
