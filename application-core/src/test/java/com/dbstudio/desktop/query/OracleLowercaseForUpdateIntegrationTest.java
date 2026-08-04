package com.dbstudio.desktop.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.oracle.common.OracleDialect;
import com.dbstudio.oracle.common.OracleMetadataAdapter;
import com.dbstudio.spi.DatabaseSession;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Real Oracle regression gate for case-insensitive unquoted identifiers in editable FOR UPDATE results. */
class OracleLowercaseForUpdateIntegrationTest {
    private static final String SQL_TEMPLATE = "select * from cbsac.sales_orders a where a.id=%s for update";
    private static final String UPPERCASE_SQL_TEMPLATE = "SELECT * FROM CBSAC.SALES_ORDERS a WHERE a.id=%s FOR UPDATE";
    private static final String EXPLICIT_SQL_TEMPLATE =
            "select a.id,a.biz_code,a.display_name from cbsac.sales_orders a where a.id=%s for update";

    @Test
    void executesLowercaseForUpdateThroughTheEditableResultPipeline() throws Exception {
        String url = System.getenv("DBSTUDIO_ORACLE_JDBC_URL");
        String user = System.getenv("DBSTUDIO_ORACLE_USERNAME");
        String password = System.getenv("DBSTUDIO_ORACLE_PASSWORD");
        String rowId = valueOrDefault(System.getenv("DBSTUDIO_ORACLE_FOR_UPDATE_TEST_ID"), "1");
        Assumptions.assumeTrue(present(url) && present(user) && password != null,
                "set DBSTUDIO_ORACLE_JDBC_URL/USERNAME/PASSWORD to run the Oracle FOR UPDATE regression gate");
        Assumptions.assumeTrue(rowId.matches("[1-9][0-9]*"), "test row id must be a positive integer");
        String sql = String.format(SQL_TEMPLATE, rowId);

        OracleDialect dialect = new OracleDialect();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            DatabaseSession session = new DatabaseSession() {
                @Override public Connection jdbcConnection() { return connection; }
                @Override public String currentCatalog() { return ""; }
                @Override public String currentSchema() { return user; }
                @Override public void close() { }
            };
            MetadataResultColumnResolver resolver = new MetadataResultColumnResolver(
                    new OracleMetadataAdapter(), session, dialect);
            try (QueryRunner runner = new QueryRunner(session, 10, 10, resolver, dialect, false)) {
                for (String query : Arrays.asList(sql, String.format(UPPERCASE_SQL_TEMPLATE, rowId))) {
                    assertEditableForUpdate(runner, resolver, dialect, query, rowId);
                }
                StatementResult explicit = assertEditableForUpdate(runner, resolver, dialect,
                        String.format(EXPLICIT_SQL_TEMPLATE, rowId), rowId);
                String displayName = "Oracle lowercase mutation regression";
                QueryRunner.MutationBatchResult applied = runner.applyResultOperations(explicit.mutationTarget(),
                        explicit.rows(), explicit.rowLocators(), Collections.singletonList(
                                new QueryRunner.ResultOperation("update-display-name", QueryRunner.MutationKind.UPDATE,
                                        0, Collections.singletonList(new QueryRunner.ValueChange(2,
                                                QueryRunner.MutationValue.text(displayName)))))).join();
                assertEquals(displayName, applied.operations().get(0).row().get(2));
            } finally {
                connection.rollback();
            }
        }
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
    private static String valueOrDefault(String value, String defaultValue) {
        return present(value) ? value.trim() : defaultValue;
    }

    private static StatementResult assertEditableForUpdate(QueryRunner runner, MetadataResultColumnResolver resolver,
                                                           OracleDialect dialect, String sql, String rowId) {
        PreparedResultQuery prepared = resolver.prepare(sql);
        String executionSql = prepared.executionSql().toUpperCase(java.util.Locale.ROOT);
        if (sql.contains("*")) assertTrue(executionSql.contains("A.*"), prepared.executionSql());
        assertTrue(executionSql.contains("ROWIDTOCHAR(A.ROWID)"), prepared.executionSql());
        assertTrue(executionSql.contains("FOR UPDATE"), prepared.executionSql());

        QueryExecution execution = runner.execute(dialect.split(sql), true).join();
        assertFalse(execution.failed(), execution.results().isEmpty() ? "无结果" : execution.results().get(0).errorMessage());
        assertEquals(1, execution.results().size());

        StatementResult result = execution.results().get(0);
        assertEquals(rowId, result.rows().get(0).get(0));
        ResultMutationTarget target = result.mutationTarget();
        assertNotNull(target);
        assertTrue(target.editableForUpdate());
        assertEquals("\"CBSAC\".\"SALES_ORDERS\"", target.qualifiedName());
        assertTrue(target.columns().stream().anyMatch(column -> "DISPLAY_NAME".equalsIgnoreCase(column.name())
                && "\"DISPLAY_NAME\"".equals(column.quotedName())));
        assertNotNull(target.locator());
        assertEquals("ROWID", target.locator().kind());
        assertTrue(target.columns().stream().anyMatch(column -> "BIZ_CODE".equalsIgnoreCase(column.name())
                && column.editable()));
        return result;
    }
}
