package com.dbstudio.desktop.query;

import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlStatement;
import java.io.IOException;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class QueryRunner implements AutoCloseable {
    public static final int DEFAULT_FETCH_SIZE = 500;
    public static final int DEFAULT_MAX_ROWS = 1_000;
    private static final int MAX_LOB_CHARACTERS = 10_000;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final DatabaseSession session;
    private final ExecutorService executor;
    private final AtomicReference<Statement> activeStatement = new AtomicReference<Statement>();
    private final AtomicBoolean transactionDirty = new AtomicBoolean();
    private volatile int maxRows;

    public QueryRunner(DatabaseSession session, int maxRows) {
        this.session = Objects.requireNonNull(session, "session");
        this.maxRows = Math.max(1, maxRows);
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "dbstudio-query-" + THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public QueryRunner(DatabaseSession session) { this(session, DEFAULT_MAX_ROWS); }

    public CompletableFuture<QueryExecution> execute(List<SqlStatement> statements, boolean stopOnError) {
        return execute(statements, stopOnError, QueryResultListener.NONE);
    }

    public CompletableFuture<QueryExecution> execute(final List<SqlStatement> statements,
                                                     final boolean stopOnError,
                                                     final QueryResultListener listener) {
        final List<SqlStatement> copied = Collections.unmodifiableList(new ArrayList<SqlStatement>(statements));
        return CompletableFuture.supplyAsync(() -> executeBlocking(copied, stopOnError, listener), executor);
    }

    public CompletableFuture<Void> commit() {
        return CompletableFuture.runAsync(() -> {
            try {
                session.commit();
                transactionDirty.set(false);
            } catch (SQLException exception) {
                throw new QueryExecutionException("提交失败：" + exception.getMessage(), exception);
            }
        }, executor);
    }

    public CompletableFuture<Void> rollback() {
        return CompletableFuture.runAsync(() -> {
            try {
                session.rollback();
                transactionDirty.set(false);
            } catch (SQLException exception) {
                throw new QueryExecutionException("回滚失败：" + exception.getMessage(), exception);
            }
        }, executor);
    }

    public boolean cancel() {
        Statement statement = activeStatement.get();
        if (statement == null) return false;
        try {
            statement.cancel();
            return true;
        } catch (SQLException exception) {
            throw new QueryExecutionException("取消执行失败：" + exception.getMessage(), exception);
        }
    }

    public boolean isRunning() { return activeStatement.get() != null; }
    public boolean isTransactionDirty() { return transactionDirty.get(); }
    public void setMaxRows(int maxRows) { this.maxRows = Math.max(1, maxRows); }

    private QueryExecution executeBlocking(List<SqlStatement> statements, boolean stopOnError,
                                           QueryResultListener listener) {
        Instant started = Instant.now();
        List<StatementResult> results = new ArrayList<StatementResult>();
        boolean cancelled = false;
        for (SqlStatement sqlStatement : statements) {
            if (Thread.currentThread().isInterrupted()) {
                cancelled = true;
                break;
            }
            List<StatementResult> statementResults = executeOne(sqlStatement, results.size(), listener);
            results.addAll(statementResults);
            StatementResult failure = null;
            for (StatementResult result : statementResults) {
                if (result.failed()) { failure = result; break; }
            }
            if (failure != null) {
                cancelled = isCancellation(failure.errorMessage());
                if (stopOnError) break;
            }
        }
        return new QueryExecution(results, Duration.between(started, Instant.now()), cancelled);
    }

    private List<StatementResult> executeOne(SqlStatement sqlStatement, int firstResultIndex,
                                             QueryResultListener listener) {
        Instant started = Instant.now();
        try (Statement statement = session.jdbcConnection().createStatement()) {
            statement.setFetchSize(DEFAULT_FETCH_SIZE);
            statement.setMaxRows(maxRows + 1);
            activeStatement.set(statement);
            boolean hasResult = statement.execute(sqlStatement.text());
            if (sqlStatement.type().modifiesData()) transactionDirty.set(true);
            else if (sqlStatement.type().implicitlyCommitsInMySql()) transactionDirty.set(false);

            List<StatementResult> outputs = new ArrayList<StatementResult>();
            while (true) {
                int resultIndex = firstResultIndex + outputs.size();
                StatementResult output;
                if (hasResult) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        output = readResultSet(sqlStatement, resultSet, started, resultIndex, listener);
                    }
                } else {
                    int updateCount = statement.getUpdateCount();
                    if (updateCount == -1) break;
                    output = new StatementResult(sqlStatement.text(), sqlStatement.type(),
                            Collections.<String>emptyList(), Collections.<List<String>>emptyList(),
                            updateCount, false, Duration.between(started, Instant.now()), null);
                    listener.resultStarted(resultIndex, sqlStatement.text(), sqlStatement.type(), output.columns());
                }
                outputs.add(output);
                listener.resultCompleted(resultIndex, output);
                hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }
            if (outputs.isEmpty()) {
                StatementResult output = new StatementResult(sqlStatement.text(), sqlStatement.type(),
                        Collections.<String>emptyList(), Collections.<List<String>>emptyList(),
                        0, false, Duration.between(started, Instant.now()), null);
                listener.resultStarted(firstResultIndex, sqlStatement.text(), sqlStatement.type(), output.columns());
                listener.resultCompleted(firstResultIndex, output);
                outputs.add(output);
            }
            return outputs;
        } catch (SQLException exception) {
            StatementResult failure = new StatementResult(sqlStatement.text(), sqlStatement.type(),
                    Collections.<String>emptyList(), Collections.<List<String>>emptyList(), -1, false,
                    Duration.between(started, Instant.now()), sanitize(exception));
            listener.resultStarted(firstResultIndex, sqlStatement.text(), sqlStatement.type(), failure.columns());
            listener.resultCompleted(firstResultIndex, failure);
            return Collections.singletonList(failure);
        } finally {
            activeStatement.set(null);
        }
    }

    private StatementResult readResultSet(SqlStatement sqlStatement, ResultSet resultSet, Instant started,
                                          int resultIndex, QueryResultListener listener) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = new ArrayList<String>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            String label = metadata.getColumnLabel(index);
            columns.add(label == null || label.trim().isEmpty() ? metadata.getColumnName(index) : label);
        }
        listener.resultStarted(resultIndex, sqlStatement.text(), sqlStatement.type(),
                Collections.unmodifiableList(new ArrayList<String>(columns)));

        List<List<String>> rows = new ArrayList<List<String>>(Math.min(maxRows, DEFAULT_FETCH_SIZE));
        List<List<String>> batch = new ArrayList<List<String>>(DEFAULT_FETCH_SIZE);
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) { truncated = true; break; }
            List<String> row = new ArrayList<String>(columnCount);
            for (int index = 1; index <= columnCount; index++) row.add(displayValue(resultSet.getObject(index)));
            rows.add(row);
            batch.add(Collections.unmodifiableList(new ArrayList<String>(row)));
            if (batch.size() == DEFAULT_FETCH_SIZE) {
                listener.rows(resultIndex, immutableRows(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) listener.rows(resultIndex, immutableRows(batch));
        return new StatementResult(sqlStatement.text(), sqlStatement.type(), columns, rows, -1, truncated,
                Duration.between(started, Instant.now()), null);
    }

    private static List<List<String>> immutableRows(List<List<String>> rows) {
        List<List<String>> copied = new ArrayList<List<String>>(rows.size());
        for (List<String> row : rows) copied.add(Collections.unmodifiableList(new ArrayList<String>(row)));
        return Collections.unmodifiableList(copied);
    }

    public static String displayValue(Object value) throws SQLException {
        if (value == null) return null;
        if (value instanceof byte[]) return "0x" + toHex((byte[]) value);
        if (value instanceof Blob) {
            Blob blob = (Blob) value;
            long length = Math.min(blob.length(), MAX_LOB_CHARACTERS);
            return "0x" + toHex(blob.getBytes(1, (int) length));
        }
        if (value instanceof Clob) {
            Clob clob = (Clob) value;
            try (Reader reader = clob.getCharacterStream()) {
                return readCharacters(reader);
            } catch (IOException exception) {
                throw new SQLException("读取 CLOB 失败", exception);
            }
        }
        return value.toString();
    }

    private static String toHex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            result[i * 2] = digits[value >>> 4];
            result[i * 2 + 1] = digits[value & 0x0f];
        }
        return new String(result);
    }

    private static String readCharacters(Reader reader) throws IOException {
        char[] buffer = new char[2_048];
        StringBuilder result = new StringBuilder();
        int read;
        while (result.length() < MAX_LOB_CHARACTERS
                && (read = reader.read(buffer, 0, Math.min(buffer.length,
                MAX_LOB_CHARACTERS - result.length()))) >= 0) {
            result.append(buffer, 0, read);
        }
        return result.toString();
    }

    private static String sanitize(SQLException exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }

    private static boolean isCancellation(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("cancel") || lower.contains("interrupt");
    }

    @Override
    public void close() {
        try { cancel(); } catch (RuntimeException ignored) { }
        executor.shutdownNow();
        try { session.close(); } catch (SQLException ignored) { }
    }

    public static final class QueryExecutionException extends RuntimeException {
        public QueryExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
