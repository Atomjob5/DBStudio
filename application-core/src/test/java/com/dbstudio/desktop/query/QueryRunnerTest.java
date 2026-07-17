package com.dbstudio.desktop.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class QueryRunnerTest {
    @Test
    void limitsRowsStreamsBatchesAndTracksTransactionState() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        DatabaseSession session = new DatabaseSession() {
            @Override public Connection jdbcConnection() { return connection; }
            @Override public String currentCatalog() { return ""; }
            @Override public void close() throws java.sql.SQLException { connection.close(); }
        };
        try (QueryRunner runner = new QueryRunner(session, 2)) {
            QueryExecution setup = runner.execute(Arrays.asList(
                    sql("CREATE TABLE sample(id INTEGER)", StatementType.DDL),
                    sql("INSERT INTO sample VALUES (1), (2), (3)", StatementType.INSERT)), true).join();
            assertFalse(setup.failed());
            assertTrue(runner.isTransactionDirty());

            final int[] streamed = { 0 };
            StatementResult query = runner.execute(Collections.singletonList(
                    sql("SELECT id FROM sample ORDER BY id", StatementType.QUERY)), true,
                    new QueryResultListener() {
                        @Override public void resultStarted(int index, String sql, StatementType type,
                                                            java.util.List<String> columns) { }
                        @Override public void rows(int index, java.util.List<java.util.List<String>> rows) {
                            streamed[0] += rows.size();
                        }
                        @Override public void resultCompleted(int index, StatementResult result) { }
                    }).join().results().get(0);
            assertEquals(2, query.rows().size());
            assertEquals(2, streamed[0]);
            assertTrue(query.truncated());

            runner.rollback().join();
            assertFalse(runner.isTransactionDirty());
        }
    }

    private SqlStatement sql(String text, StatementType type) {
        return new SqlStatement(text, 0, text.length(), type);
    }
}
