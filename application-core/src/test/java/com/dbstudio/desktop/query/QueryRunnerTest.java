package com.dbstudio.desktop.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import javax.sql.rowset.serial.SerialClob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

class QueryRunnerTest {
    @Test
    void appliesConfiguredClobPreviewLengthToLargeJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
        List<String> records = new ArrayList<String>();
        for (int index = 0; index < 500; index++) {
            records.add("record-" + index + "-中文内容-abcdefghijklmnopqrstuvwxyz");
        }
        payload.put("requestId", "clob-preview-regression");
        payload.put("records", records);
        payload.put("tail", "完整 JSON 尾字段");
        String json = mapper.writeValueAsString(payload);
        assertTrue(json.length() > 20_000);

        assertEquals(10_000, QueryRunner.displayValue(new SerialClob(json.toCharArray())).length());
        String expanded = QueryRunner.displayValue(new SerialClob(json.toCharArray()), 30_000);
        assertEquals(json, expanded);
        JsonNode parsed = mapper.readTree(expanded);
        assertEquals("clob-preview-regression", parsed.get("requestId").asText());
        assertEquals("完整 JSON 尾字段", parsed.get("tail").asText());
        assertEquals(records.size(), parsed.get("records").size());
    }

    @Test
    void validatesConfiguredClobPreviewAgainstRealOracleJsonWhenConfigured() throws Exception {
        String url = System.getenv("DBSTUDIO_ORACLE_JDBC_URL");
        String user = System.getenv("DBSTUDIO_ORACLE_USERNAME");
        String password = System.getenv("DBSTUDIO_ORACLE_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.trim().isEmpty()
                && user != null && !user.trim().isEmpty() && password != null,
                "set DBSTUDIO_ORACLE_JDBC_URL/USERNAME/PASSWORD to run the real CLOB JSON validation");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode records = payload.putArray("records");
        for (int index = 0; index < 500; index++) {
            records.add("record-" + index + "-中文内容-abcdefghijklmnopqrstuvwxyz");
        }
        payload.put("tail", "完整 JSON 尾字段");
        String json = mapper.writeValueAsString(payload);
        assertTrue(json.length() > 20_000);

        String table = "DBS_CLOB_" + java.util.UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(java.util.Locale.ROOT);
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + table + " (payload CLOB)");
            }
            try {
                try (java.sql.PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO " + table + " (payload) VALUES (?)")) {
                    insert.setCharacterStream(1, new java.io.StringReader(json), json.length());
                    assertEquals(1, insert.executeUpdate());
                }
                try (java.sql.Statement statement = connection.createStatement();
                     java.sql.ResultSet result = statement.executeQuery("SELECT payload FROM " + table)) {
                    assertTrue(result.next());
                    assertEquals(10_000, QueryRunner.displayValue(result.getClob(1)).length());
                }
                try (java.sql.Statement statement = connection.createStatement();
                     java.sql.ResultSet result = statement.executeQuery("SELECT payload FROM " + table)) {
                    assertTrue(result.next());
                    String expanded = QueryRunner.displayValue(result.getClob(1), 30_000);
                    assertEquals(json, expanded);
                    JsonNode parsed = mapper.readTree(expanded);
                    assertEquals("完整 JSON 尾字段", parsed.get("tail").asText());
                    assertEquals(500, parsed.get("records").size());
                }
            } finally {
                try (java.sql.Statement statement = connection.createStatement()) {
                    statement.execute("DROP TABLE " + table + " PURGE");
                }
            }
        }
    }

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
    void forwardsTheOriginalStatementRangeWithResultMetadata() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        DatabaseSession session = session(connection);
        try (QueryRunner runner = new QueryRunner(session, 10)) {
            final int[] range = { -1, -1 };
            runner.execute(Collections.singletonList(new SqlStatement("SELECT 1", 12, 20, StatementType.QUERY)), true,
                    new QueryResultListener() {
                        @Override public void resultStarted(int index, String sql, StatementType type,
                                                            java.util.List<String> columns) { }
                        @Override public void resultMetadata(int index, SqlStatement statement,
                                                             List<ResultColumn> columns,
                                                             ResultMutationTarget mutationTarget) {
                            range[0] = statement.startOffset();
                            range[1] = statement.endOffset();
                        }
                        @Override public void rows(int index, java.util.List<java.util.List<String>> rows) { }
                        @Override public void resultCompleted(int index, StatementResult result) { }
                    }).join();
            assertEquals(12, range[0]);
            assertEquals(20, range[1]);
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
    void acceptsOceanBaseOracleSavepointReleaseLimitation() throws Exception {
        final Connection physical = DriverManager.getConnection("jdbc:sqlite::memory:");
        physical.setAutoCommit(false);
        AtomicInteger releaseAttempts = new AtomicInteger();
        SQLException unsupported = new SQLException("conn=1292666 releaseSavepoint is not supported", "99999", 17023);
        Connection connection = connectionWithSavepointReleaseFailure(physical, releaseAttempts, unsupported);
        try (QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            runner.execute(Arrays.asList(
                    sql("CREATE TABLE oceanbase_editable(id INTEGER PRIMARY KEY, name TEXT)", StatementType.DDL),
                    sql("INSERT INTO oceanbase_editable VALUES (1, 'before')", StatementType.INSERT)), true).join();
            runner.commit().join();
            StatementResult result = runner.execute(Collections.singletonList(
                    sql("SELECT id, name FROM oceanbase_editable", StatementType.QUERY)), true)
                    .join().results().get(0);

            runner.applyResultChanges(editableTarget("\"oceanbase_editable\""), result.rows(),
                    Collections.singletonList(new QueryRunner.RowChange(0, Collections.singletonList(
                            new QueryRunner.CellChange(1, "after"))))).join();

            assertEquals(1, releaseAttempts.get());
            assertTrue(runner.isTransactionDirty());
            assertEquals("after", runner.execute(Collections.singletonList(
                    sql("SELECT name FROM oceanbase_editable", StatementType.QUERY)), true)
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
    void appliesOrderedUpdateInsertDeleteOperationsAndReturnsAuthoritativeRows() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (QueryRunner runner = new QueryRunner(session(connection), 20, 20)) {
            runner.execute(Arrays.asList(
                    sql("CREATE TABLE operation_sample(id INTEGER PRIMARY KEY, name TEXT NOT NULL DEFAULT 'db-default')",
                            StatementType.DDL),
                    sql("INSERT INTO operation_sample VALUES (1, 'one'), (2, 'two')", StatementType.INSERT)), true)
                    .join();
            runner.commit().join();
            StatementResult source = runner.execute(Collections.singletonList(
                    sql("SELECT id, name FROM operation_sample ORDER BY id", StatementType.QUERY)), true)
                    .join().results().get(0);
            ResultMutationTarget target = editableTarget("\"operation_sample\"");
            List<QueryRunner.ResultOperation> operations = Arrays.asList(
                    new QueryRunner.ResultOperation("update-1", QueryRunner.MutationKind.UPDATE, 0,
                            Collections.singletonList(new QueryRunner.ValueChange(1,
                                    QueryRunner.MutationValue.text("updated")))),
                    new QueryRunner.ResultOperation("insert-3", QueryRunner.MutationKind.INSERT, -1,
                            Arrays.asList(new QueryRunner.ValueChange(0, QueryRunner.MutationValue.text("3")),
                                    new QueryRunner.ValueChange(1, QueryRunner.MutationValue.text("three")))),
                    new QueryRunner.ResultOperation("delete-2", QueryRunner.MutationKind.DELETE, 1,
                            Collections.<QueryRunner.ValueChange>emptyList()));

            QueryRunner.MutationBatchResult applied = runner.applyResultOperations(
                    target, source.rows(), operations).join();

            assertEquals(Arrays.asList("1", "updated"), applied.operations().get(0).row());
            assertEquals(Arrays.asList("3", "three"), applied.operations().get(1).row());
            assertEquals(Arrays.asList(Arrays.asList("1", "updated"), Arrays.asList("3", "three")),
                    runner.execute(Collections.singletonList(sql(
                            "SELECT id, name FROM operation_sample ORDER BY id", StatementType.QUERY)), true)
                            .join().results().get(0).rows());
            runner.commit().join();

            try {
                runner.applyResultOperations(target, Arrays.asList(
                        Arrays.asList("1", "updated"), Arrays.asList("3", "three")), Arrays.asList(
                        new QueryRunner.ResultOperation("update-before-error", QueryRunner.MutationKind.UPDATE, 0,
                                Collections.singletonList(new QueryRunner.ValueChange(1,
                                        QueryRunner.MutationValue.text("must-rollback")))),
                        new QueryRunner.ResultOperation("duplicate", QueryRunner.MutationKind.INSERT, -1,
                                Arrays.asList(new QueryRunner.ValueChange(0, QueryRunner.MutationValue.text("3")),
                                        new QueryRunner.ValueChange(1, QueryRunner.MutationValue.text("duplicate"))))))
                        .join();
                throw new AssertionError("expected the atomic batch to fail");
            } catch (CompletionException expected) {
                QueryRunner.QueryExecutionException failure =
                        (QueryRunner.QueryExecutionException) expected.getCause();
                assertEquals("duplicate", failure.operationId());
            }
            assertEquals("updated", runner.execute(Collections.singletonList(sql(
                    "SELECT name FROM operation_sample WHERE id=1", StatementType.QUERY)), true)
                    .join().results().get(0).rows().get(0).get(0));

            List<QueryRunner.MutationPreview> previews = QueryRunner.previewResultOperations(
                    target, source.rows(), operations);
            assertTrue(previews.get(0).sql().contains("SET \"name\" = ?"));
            assertFalse(previews.get(0).sql().contains("updated"));
        }
    }

    @Test
    void rereadsComputedColumnsAndReportsRowsThatLeaveTheOriginalFilter() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (QueryRunner runner = new QueryRunner(session(connection), 20, 20)) {
            runner.execute(Arrays.asList(
                    sql("CREATE TABLE filtered_edit(id INTEGER PRIMARY KEY, name TEXT NOT NULL)", StatementType.DDL),
                    sql("INSERT INTO filtered_edit VALUES (1, 'keep-one')", StatementType.INSERT)), true).join();
            runner.commit().join();
            List<ResultMutationTarget.Column> columns = Arrays.asList(
                    new ResultMutationTarget.Column(0, "id", "\"id\"", Types.INTEGER,
                            "INTEGER", "number", 0, 0, false, "", false, false, true, ""),
                    new ResultMutationTarget.Column(1, "name", "\"name\"", Types.VARCHAR,
                            "TEXT", "text", 0, 0, false, "", false, false, true, ""),
                    new ResultMutationTarget.Column(2, "name_length", "", Types.INTEGER,
                            "INTEGER", "number", 0, 0, true, "", false, false, false, "计算表达式只读"));
            ResultMutationTarget.Locator locator = new ResultMutationTarget.Locator("UNIQUE_KEY",
                    Collections.singletonList("\"id\" = ?"), Collections.singletonList(Types.INTEGER),
                    Collections.singletonList("id"));
            ResultMutationTarget target = new ResultMutationTarget("\"filtered_edit\"", columns,
                    Collections.singletonList(new ResultMutationTarget.Key("PRIMARY", true,
                            Collections.singletonList(0))), true, "editable", "", "", "WAIT",
                    true, true, locator, false,
                    "SELECT id, name, length(name) FROM filtered_edit WHERE name LIKE 'keep%' AND id = ?");
            List<QueryRunner.ResultOperation> operations = Arrays.asList(
                    new QueryRunner.ResultOperation("leave-filter", QueryRunner.MutationKind.UPDATE, 0,
                            Collections.singletonList(new QueryRunner.ValueChange(1,
                                    QueryRunner.MutationValue.text("gone")))),
                    new QueryRunner.ResultOperation("visible-insert", QueryRunner.MutationKind.INSERT, -1,
                            Arrays.asList(new QueryRunner.ValueChange(0, QueryRunner.MutationValue.text("2")),
                                    new QueryRunner.ValueChange(1, QueryRunner.MutationValue.text("keep-two")))));

            QueryRunner.MutationBatchResult applied = runner.applyResultOperations(target,
                    Collections.singletonList(Arrays.asList("1", "keep-one", "8")),
                    Collections.singletonList(Collections.singletonList("1")), operations).join();

            assertFalse(applied.operations().get(0).visible());
            assertTrue(applied.operations().get(0).row().isEmpty());
            assertTrue(applied.operations().get(1).visible());
            assertEquals(Arrays.asList("2", "keep-two", "8"), applied.operations().get(1).row());
            runner.rollback().join();
        }
    }

    @Test
    void reportsTypedFieldValidationAtTheOperationAndColumn() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            runner.execute(Arrays.asList(
                    sql("CREATE TABLE typed_edit(id INTEGER PRIMARY KEY, status TEXT NOT NULL)", StatementType.DDL),
                    sql("INSERT INTO typed_edit VALUES (1, 'NEW')", StatementType.INSERT)), true).join();
            ResultMutationTarget target = new ResultMutationTarget("\"typed_edit\"", Arrays.asList(
                    new ResultMutationTarget.Column(0, "id", "\"id\"", Types.INTEGER,
                            "INTEGER", "number", 10, 0, false, "", false, false, true, ""),
                    new ResultMutationTarget.Column(1, "status", "\"status\"", Types.VARCHAR,
                            "enum('NEW','DONE')", "text", 10, 0, false, "", false, false, true, "")),
                    Collections.singletonList(new ResultMutationTarget.Key("PRIMARY", true,
                            Collections.singletonList(0))), true);
            try {
                runner.applyResultOperations(target, Collections.singletonList(Arrays.asList("1", "NEW")),
                        Collections.singletonList(new QueryRunner.ResultOperation("invalid-enum",
                                QueryRunner.MutationKind.UPDATE, 0,
                                Collections.singletonList(new QueryRunner.ValueChange(1,
                                        QueryRunner.MutationValue.text("UNKNOWN")))))).join();
                throw new AssertionError("expected enum validation to fail");
            } catch (CompletionException expected) {
                QueryRunner.QueryExecutionException failure =
                        (QueryRunner.QueryExecutionException) expected.getCause();
                assertEquals("invalid-enum", failure.operationId());
                assertEquals(Integer.valueOf(1), failure.columnIndex());
                assertTrue(failure.getMessage().contains("NEW"));
            }
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
    void continuesAfterStatementFailureWhenStopOnErrorIsDisabled() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (final QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            QueryExecution continued = runner.execute(Arrays.asList(
                    sql("SELECT 1", StatementType.QUERY),
                    sql("SELECT * FROM missing_table", StatementType.QUERY),
                    sql("SELECT 2", StatementType.QUERY)), false).join();

            assertEquals(3, continued.results().size());
            assertFalse(continued.results().get(0).failed());
            assertTrue(continued.results().get(1).failed());
            assertFalse(continued.results().get(2).failed());
            assertEquals("2", continued.results().get(2).rows().get(0).get(0));

            QueryExecution stopped = runner.execute(Arrays.asList(
                    sql("SELECT 1", StatementType.QUERY),
                    sql("SELECT * FROM missing_table", StatementType.QUERY),
                    sql("SELECT 2", StatementType.QUERY)), true).join();
            assertEquals(2, stopped.results().size());
            assertTrue(stopped.results().get(1).failed());
        }
    }

    @Test
    void cancellationStillStopsAContinuedBatchAfterAStatementFailure() throws Exception {
        final Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.setAutoCommit(false);
        try (final QueryRunner runner = new QueryRunner(session(connection), 10, 10)) {
            AtomicInteger completed = new AtomicInteger();
            QueryExecution execution = runner.execute(Arrays.asList(
                    sql("SELECT * FROM missing_table", StatementType.QUERY),
                    sql("SELECT 2", StatementType.QUERY)), false, new QueryResultListener() {
                        @Override public void resultStarted(int index, String sql, StatementType type,
                                                            List<String> columns) { }
                        @Override public void rows(int index, List<List<String>> rows) { }
                        @Override public void resultCompleted(int index, StatementResult result) {
                            if (completed.incrementAndGet() == 1) assertTrue(runner.cancel());
                        }
                    }).join();

            assertTrue(execution.cancelled());
            assertEquals(1, execution.results().size());
            assertTrue(execution.results().get(0).failed());
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
        return connectionWithSavepointReleaseFailure(delegate, attempts,
                new SQLFeatureNotSupportedException("不支持的特性: releaseSavepoint"));
    }

    private static Connection connectionWithSavepointReleaseFailure(final Connection delegate,
                                                                     final AtomicInteger attempts,
                                                                     final SQLException failure) {
        return (Connection) Proxy.newProxyInstance(
                QueryRunnerTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, arguments) -> {
                    if ("releaseSavepoint".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw failure;
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static ResultMutationTarget editableTarget(String table) {
        return new ResultMutationTarget(table, Arrays.asList(
                new ResultMutationTarget.Column(0, "id", "\"id\"", Types.INTEGER),
                new ResultMutationTarget.Column(1, "name", "\"name\"", Types.VARCHAR)),
                Collections.singletonList(new ResultMutationTarget.Key(
                        "pk", true, Collections.singletonList(0))), true);
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
