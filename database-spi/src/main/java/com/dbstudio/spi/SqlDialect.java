package com.dbstudio.spi;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** SQL 方言适配器：统一标识符引用、脚本拆分、分类、事务影响判断和安全 SQL 文本生成。 */
public interface SqlDialect {
    String id();

    String quoteIdentifier(String identifier);

    Set<String> keywords();

    List<SqlStatement> split(String script);

    Optional<SqlStatement> currentStatement(String script, int cursorOffset);

    StatementType classify(String sql);

    String format(String sql);

    /**
     * Returns whether this statement is an UPDATE or DELETE without a top-level WHERE predicate.
     * Implementations with an SQL parser should override this method so comments, literals and
     * nested queries do not affect the assessment.
     */
    default boolean requiresWhereClauseConfirmation(SqlStatement statement) {
        return false;
    }

    default String compact(String sql) {
        return format(sql);
    }

    default String qualifiedName(String catalog, String schema, String name) {
        StringBuilder result = new StringBuilder();
        if (catalog != null && !catalog.trim().isEmpty()) result.append(quoteIdentifier(catalog)).append('.');
        if (schema != null && !schema.trim().isEmpty()
                && (catalog == null || !schema.equalsIgnoreCase(catalog))) {
            result.append(quoteIdentifier(schema)).append('.');
        }
        return result.append(quoteIdentifier(name)).toString();
    }

    default String previewQuery(DatabaseObject object, int maxRows) {
        return "SELECT *\nFROM " + qualifiedName(object.catalog(), object.schema(), object.name());
    }

    default TransactionEffect transactionEffect(SqlStatement statement, boolean producedResultSet) {
        StatementType type = statement.type();
        if (type.modifiesData() || type == StatementType.OTHER) return TransactionEffect.DIRTY;
        if (type == StatementType.DDL) return TransactionEffect.IMPLICIT_COMMIT;
        if (type == StatementType.TRANSACTION) {
            String upper = statement.text().trim().toUpperCase(java.util.Locale.ROOT);
            return upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK")
                    ? TransactionEffect.END : TransactionEffect.DIRTY;
        }
        if (type == StatementType.QUERY && !producedResultSet) return TransactionEffect.DIRTY;
        return TransactionEffect.NONE;
    }

    default String sqlLiteral(String value, int jdbcType) {
        if (value == null) return "NULL";
        switch (jdbcType) {
            case java.sql.Types.TINYINT:
            case java.sql.Types.SMALLINT:
            case java.sql.Types.INTEGER:
            case java.sql.Types.BIGINT:
            case java.sql.Types.FLOAT:
            case java.sql.Types.REAL:
            case java.sql.Types.DOUBLE:
            case java.sql.Types.NUMERIC:
            case java.sql.Types.DECIMAL:
                if (value.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) return value;
                break;
            default:
                break;
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** 当查询投影可以安全解析时，按结果顺序返回物理来源字段名。 */
    default List<String> resultColumnNames(String sql) {
        return Collections.emptyList();
    }

    /** 只有无需猜测即可生成行级变更 SQL 时，才返回物理来源信息。 */
    default Optional<ResultMutationSource> resultMutationSource(String sql) {
        return Optional.empty();
    }

    /** Returns an explainable edit assessment even when the query must remain read-only. */
    default ResultEditPlan resultEditPlan(String sql) {
        Optional<ResultMutationSource> source = resultMutationSource(sql);
        if (!source.isPresent()) return ResultEditPlan.readOnly(
                "UNSUPPORTED_QUERY_SHAPE", "仅支持可解析的单一基表查询");
        if (!source.get().editableForUpdate()) return ResultEditPlan.readOnly(source.get(),
                "FOR_UPDATE_REQUIRED", "需要显式执行单表 FOR UPDATE 查询");
        return ResultEditPlan.editable(source.get());
    }

    /** Adds server-only locator expressions to a safe single-table query. */
    default String appendResultLocatorColumns(String sql, List<String> expressions, List<String> aliases) {
        return sql;
    }

    /** Adds a server-owned row locator predicate and removes pagination for authoritative rereads. */
    default String appendResultLocatorPredicate(String sql, List<String> predicates) {
        return "";
    }

    /** Returns the source qualifier exactly as it should appear in a rewritten query. */
    default String resultMutationQualifier(String sql, ResultMutationSource source) {
        if (source == null) return "";
        String qualifier = source.alias().isEmpty() ? source.table() : source.alias();
        return qualifier.isEmpty() ? "" : quoteIdentifier(qualifier);
    }
}
