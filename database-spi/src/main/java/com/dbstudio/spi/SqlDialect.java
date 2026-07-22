package com.dbstudio.spi;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface SqlDialect {
    String id();

    String quoteIdentifier(String identifier);

    Set<String> keywords();

    List<SqlStatement> split(String script);

    Optional<SqlStatement> currentStatement(String script, int cursorOffset);

    StatementType classify(String sql);

    String format(String sql);

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

    /** Physical source column names in result order when the projection can be resolved safely. */
    default List<String> resultColumnNames(String sql) {
        return Collections.emptyList();
    }

    /** Returns a physical source only when row mutation SQL can be generated without guessing. */
    default Optional<ResultMutationSource> resultMutationSource(String sql) {
        return Optional.empty();
    }
}
