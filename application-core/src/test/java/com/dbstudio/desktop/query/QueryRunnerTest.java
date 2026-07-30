package com.dbstudio.desktop.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void separatesDisplayLimitFromStreamingBatchSizeAndSnapshotsSettings() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        DatabaseSession session = session(connection);
        try (final QueryRunner runner = new QueryRunner(session, 1000, 100)) {
            List<Integer> batches = new ArrayList<Integer>();
            StatementResult result = query(runner, 250, batches, null);
            assertEquals(Arrays.asList(100, 100, 50), batches);
            assertEquals(250, result.rows().size());
            assertFalse(result.truncated());

            runner.setMaxRows(120);
            runner.setStreamBatchRows(500);
            batches.clear();
            result = query(runner, 250, batches, null);
            assertEquals(Collections.singletonList(120), batches);
            assertEquals(120, result.rows().size());
            assertTrue(result.truncated());

            runner.setMaxRows(1000);
            runner.setStreamBatchRows(1);
            batches.clear();
            query(runner, 3, batches, null);
            assertEquals(Arrays.asList(1, 1, 1), batches);

            runner.setMaxRows(1000);
            runner.setStreamBatchRows(100);
            batches.clear();
            result = query(runner, 250, batches, new Runnable() {
                @Override public void run() {
                    runner.setMaxRows(1);
                    runner.setStreamBatchRows(1);
                }
            });
            assertEquals(Arrays.asList(100, 100, 50), batches);
            assertEquals(250, result.rows().size());

            batches.clear();
            result = query(runner, 3, batches, null);
            assertEquals(Collections.singletonList(1), batches);
            assertEquals(1, result.rows().size());
            assertTrue(result.truncated());
        }
    }

    @Test
    void fetchesAdditionalPagesOnTheEditorSession() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (QueryRunner runner = new QueryRunner(session(connection), 2, 100)) {
            runner.execute(Arrays.asList(
                    sql("CREATE TABLE page_sample(id INTEGER)", StatementType.DDL),
                    sql("INSERT INTO page_sample VALUES (1), (2), (3), (4), (5)", StatementType.INSERT)), true).join();
            StatementResult initial = runner.execute(Collections.singletonList(
                    sql("SELECT id FROM page_sample ORDER BY id", StatementType.QUERY)), true).join().results().get(0);
            assertEquals(Arrays.asList("1"), initial.rows().get(0));
            assertEquals(Arrays.asList("2"), initial.rows().get(1));

            QueryRunner.PageResult second = runner.fetchPage(initial.sql(), 2, 2).join();
            assertEquals(Arrays.asList("3"), second.rows().get(0));
            assertEquals(Arrays.asList("4"), second.rows().get(1));
            assertTrue(second.hasMore());

            QueryRunner.PageResult last = runner.fetchPage(initial.sql(), 4, 2).join();
            assertEquals(Collections.singletonList(Arrays.asList("5")), last.rows());
            assertFalse(last.hasMore());
        }
    }

    @Test
    void conservativelyPinsWithPrefixedDmlEvenWhenClassifiedAsQuery() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            runner.execute(Collections.singletonList(
                    sql("CREATE TABLE cte_dml(id INTEGER)", StatementType.DDL)), true).join();
            assertFalse(runner.isTransactionDirty());

            runner.execute(Collections.singletonList(sql(
                    "WITH value(id) AS (SELECT 1) INSERT INTO cte_dml SELECT id FROM value",
                    StatementType.QUERY)), true).join();

            assertTrue(runner.isTransactionDirty());
        }
    }

    @Test
    void doesNotMarkTransactionsDirtyWhenJdbcAutoCommitIsEnabled() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(true);
        try (QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            QueryExecution execution = runner.execute(Arrays.asList(
                    sql("CREATE TABLE auto_commit_sample(id INTEGER)", StatementType.DDL),
                    sql("INSERT INTO auto_commit_sample VALUES (1)", StatementType.INSERT)), true).join();

            assertFalse(execution.failed());
            assertFalse(runner.isTransactionDirty());
            assertEquals("1", runner.execute(Collections.singletonList(
                    sql("SELECT COUNT(*) FROM auto_commit_sample", StatementType.QUERY)), true)
                    .join().results().get(0).rows().get(0).get(0));
        }
    }

    @Test
    void appliesEditableResultChangesInsideTheCurrentTransactionAndRollsBack() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            runner.execute(Arrays.asList(
                    sql("CREATE TABLE editable_sample(id INTEGER PRIMARY KEY, name TEXT)", StatementType.DDL),
                    sql("INSERT INTO editable_sample VALUES (1, 'before')", StatementType.INSERT)), true).join();
            runner.commit().join();
            StatementResult result = runner.execute(Collections.singletonList(
                    sql("SELECT id, name FROM editable_sample", StatementType.QUERY)), true).join().results().get(0);
            ResultMutationTarget target = new ResultMutationTarget("\"editable_sample\"", Arrays.asList(
                    new ResultMutationTarget.Column(0, "id", "\"id\"", Types.INTEGER),
                    new ResultMutationTarget.Column(1, "name", "\"name\"", Types.VARCHAR)),
                    Collections.singletonList(new ResultMutationTarget.Key(
                            "pk", true, Collections.singletonList(0))), true);

            runner.applyResultChanges(target, result.rows(), Collections.singletonList(
                    new QueryRunner.RowChange(0, Collections.singletonList(
                            new QueryRunner.CellChange(1, "after"))))).join();

            assertTrue(runner.isTransactionDirty());
            assertEquals("after", runner.execute(Collections.singletonList(
                    sql("SELECT name FROM editable_sample", StatementType.QUERY)), true)
                    .join().results().get(0).rows().get(0).get(0));
            runner.rollback().join();
            assertEquals("before", runner.execute(Collections.singletonList(
                    sql("SELECT name FROM editable_sample", StatementType.QUERY)), true)
                    .join().results().get(0).rows().get(0).get(0));
        }
    }

    @Test
    void acceptsDriversThatDoNotSupportReleasingSavepoints() throws Exception {
        final Connection physical = DriverManager.getConnection("jdbc:sqlite::memory:");
        physical.setAutoCommit(false);
        AtomicInteger releaseAttempts = new AtomicInteger();
        Connection connection = connectionWithoutSavepointRelease(physical, releaseAttempts);
        try (QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            runner.execute(Arrays.asList(
                    sql("CREATE TABLE oracle_editable(id INTEGER PRIMARY KEY, name TEXT)", StatementType.DDL),
                    sql("INSERT INTO oracle_editable VALUES (1, 'before')", StatementType.INSERT)), true).join();
            runner.commit().join();
            StatementResult result = runner.execute(Collections.singletonList(
                    sql("SELECT id, name FROM oracle_editable", StatementType.QUERY)), true)
                    .join().results().get(0);
            ResultMutationTarget target = new ResultMutationTarget("\"oracle_editable\"", Arrays.asList(
                    new ResultMutationTarget.Column(0, "id", "\"id\"", Types.INTEGER),
                    new ResultMutationTarget.Column(1, "name", "\"name\"", Types.VARCHAR)),
                    Collections.singletonList(new ResultMutationTarget.Key(
                            "pk", true, Collections.singletonList(0))), true);

            runner.applyResultChanges(target, result.rows(), Collections.singletonList(
                    new QueryRunner.RowChange(0, Collections.singletonList(
                            new QueryRunner.CellChange(1, "after"))))).join();

            assertEquals(1, releaseAttempts.get());
            assertTrue(runner.isTransactionDirty());
            assertEquals("after", runner.execute(Collections.singletonList(
                    sql("SELECT name FROM oracle_editable", StatementType.QUERY)), true)
                    .join().results().get(0).rows().get(0).get(0));
            runner.rollback().join();
            assertEquals("before", runner.execute(Collections.singletonList(
                    sql("SELECT name FROM oracle_editable", StatementType.QUERY)), true)
                    .join().results().get(0).rows().get(0).get(0));
        }
    }

    @Test
    void rollsBackTheWholeResultChangeBatchWhenOneRowCannotBeLocated() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            runner.execute(Arrays.asList(
                    sql("CREATE TABLE editable_batch(id INTEGER PRIMARY KEY, name TEXT)", StatementType.DDL),
                    sql("INSERT INTO editable_batch VALUES (1, 'one'), (2, 'two')", StatementType.INSERT)), true)
                    .join();
            runner.commit().join();
            ResultMutationTarget target = new ResultMutationTarget("\"editable_batch\"", Arrays.asList(
                    new ResultMutationTarget.Column(0, "id", "\"id\"", Types.INTEGER),
                    new ResultMutationTarget.Column(1, "name", "\"name\"", Types.LONGVARCHAR)),
                    Collections.singletonList(new ResultMutationTarget.Key(
                            "pk", true, Collections.singletonList(0))), true);
            List<List<String>> staleRows = Arrays.asList(
                    Arrays.asList("1", "one"), Arrays.asList("99", "missing"));

            try {
                runner.applyResultChanges(target, staleRows, Arrays.asList(
                        new QueryRunner.RowChange(0, Collections.singletonList(
                                new QueryRunner.CellChange(1, "changed"))),
                        new QueryRunner.RowChange(1, Collections.singletonList(
                                new QueryRunner.CellChange(1, "never"))))).join();
                throw new AssertionError("expected the batch to fail");
            } catch (CompletionException expected) {
                assertTrue(expected.getCause() instanceof QueryRunner.QueryExecutionException);
            }

            assertEquals("one", runner.execute(Collections.singletonList(
                    sql("SELECT name FROM editable_batch WHERE id = 1", StatementType.QUERY)), true)
                    .join().results().get(0).rows().get(0).get(0));
        }
    }

    @Test
    void acceptsCancellationBeforeStatementCreationAndResetsForTheNextExecution() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (final QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            QueryExecution cancelled = runner.execute(Collections.singletonList(
                    sql("SELECT 1", StatementType.QUERY)), true, QueryResultListener.NONE,
                    new Runnable() {
                        @Override public void run() {
                            assertTrue(runner.cancel());
                        }
                    }).join();

            assertTrue(cancelled.cancelled());
            assertTrue(cancelled.results().isEmpty());
            assertFalse(runner.isRunning());

            QueryExecution next = runner.execute(Collections.singletonList(
                    sql("SELECT 2", StatementType.QUERY)), true).join();
            assertFalse(next.cancelled());
            assertFalse(next.failed());
            assertEquals("2", next.results().get(0).rows().get(0).get(0));
        }
    }

    @Test
    void acceptsPaginationCancellationBeforeStatementCreationAndResetsForTheNextPage() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (final QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            QueryRunner.PageResult cancelled = runner.fetchPage("SELECT 1", 0, 10, new Runnable() {
                @Override public void run() {
                    assertTrue(runner.cancel());
                }
            }).join();

            assertTrue(cancelled.cancelled());
            assertTrue(cancelled.rows().isEmpty());
            assertTrue(cancelled.hasMore());
            assertFalse(runner.isRunning());

            QueryRunner.PageResult next = runner.fetchPage("SELECT 2", 0, 10).join();
            assertFalse(next.cancelled());
            assertFalse(next.hasMore());
            assertEquals("2", next.rows().get(0).get(0));
        }
    }

    private static Connection connectionWithoutSavepointRelease(final Connection delegate,
                                                                final AtomicInteger attempts) {
        return (Connection) Proxy.newProxyInstance(
                QueryRunnerTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, arguments) -> {
                    if ("releaseSavepoint".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw new SQLFeatureNotSupportedException("不支持的特性: releaseSavepoint");
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private StatementResult query(QueryRunner runner, int rows, final List<Integer> batches,
                                  final Runnable started) {
        String text = "WITH RECURSIVE numbers(id) AS (SELECT 1 UNION ALL SELECT id + 1 FROM numbers WHERE id < "
                + rows + ") SELECT id FROM numbers";
        return runner.execute(Collections.singletonList(sql(text, StatementType.QUERY)), true,
                new QueryResultListener() {
                    @Override public void resultStarted(int index, String sql, StatementType type,
                                                        java.util.List<String> columns) {
                        if (started != null) started.run();
                    }
                    @Override public void rows(int index, java.util.List<java.util.List<String>> values) {
                        batches.add(values.size());
                    }
                    @Override public void resultCompleted(int index, StatementResult result) { }
                }).join().results().get(0);
    }

    private DatabaseSession session(final Connection connection) {
        return new DatabaseSession() {
            @Override public Connection jdbcConnection() { return connection; }
            @Override public String currentCatalog() { return ""; }
            @Override public void close() throws java.sql.SQLException { connection.close(); }
        };
    }

    private SqlStatement sql(String text, StatementType type) {
        return new SqlStatement(text, 0, text.length(), type);
    }
}
