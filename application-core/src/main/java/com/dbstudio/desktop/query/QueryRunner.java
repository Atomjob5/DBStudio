package com.dbstudio.desktop.query;

import com.dbstudio.desktop.logging.SqlLogSupport;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.TransactionEffect;
import java.io.IOException;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 单编辑器 SQL 执行器。
 *
 * <p>所有操作都提交到同一个单线程执行器，保证同一 JDBC 会话上的 SQL、事务、分页和取消
 * 按顺序执行。结果读取采用固定 JDBC 抓取大小，并使用独立的展示上限和 WebSocket 批次。</p>
 */
public final class QueryRunner implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(QueryRunner.class);
    public static final int JDBC_FETCH_SIZE = 500;
    public static final int DEFAULT_STREAM_BATCH_ROWS = 100;
    public static final int DEFAULT_MAX_ROWS = 1_000;
    private static final int MAX_LOB_CHARACTERS = 10_000;
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final DatabaseSession session;
    private final boolean ownsSession;
    private final ExecutorService executor;
    private final AtomicReference<Statement> activeStatement = new AtomicReference<Statement>();
    private final AtomicBoolean executionActive = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();
    private final AtomicBoolean transactionDirty = new AtomicBoolean();
    private final ResultColumnResolver columnResolver;
    private final SqlDialect dialect;
    private volatile int maxRows;
    private volatile int streamBatchRows;

    public QueryRunner(DatabaseSession session, int maxRows, int streamBatchRows,
                       ResultColumnResolver columnResolver) {
        this(session, maxRows, streamBatchRows, columnResolver, null, true);
    }

    public QueryRunner(DatabaseSession session, int maxRows, int streamBatchRows,
                       ResultColumnResolver columnResolver, boolean ownsSession) {
        this(session, maxRows, streamBatchRows, columnResolver, null, ownsSession);
    }

    public QueryRunner(DatabaseSession session, int maxRows, int streamBatchRows,
                       ResultColumnResolver columnResolver, SqlDialect dialect, boolean ownsSession) {
        this.session = Objects.requireNonNull(session, "session");
        this.ownsSession = ownsSession;
        this.columnResolver = Objects.requireNonNull(columnResolver, "columnResolver");
        this.dialect = dialect;
        this.maxRows = Math.max(1, maxRows);
        this.streamBatchRows = Math.max(1, streamBatchRows);
        this.executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "dbstudio-query-" + THREAD_SEQUENCE.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public QueryRunner(DatabaseSession session, int maxRows, int streamBatchRows) {
        this(session, maxRows, streamBatchRows, ResultColumnResolver.NONE);
    }

    public QueryRunner(DatabaseSession session, int maxRows) { this(session, maxRows, DEFAULT_STREAM_BATCH_ROWS); }
    public QueryRunner(DatabaseSession session) { this(session, DEFAULT_MAX_ROWS, DEFAULT_STREAM_BATCH_ROWS); }

    public CompletableFuture<QueryExecution> execute(List<SqlStatement> statements, boolean stopOnError) {
        return execute(statements, stopOnError, QueryResultListener.NONE);
    }

    public CompletableFuture<QueryExecution> execute(final List<SqlStatement> statements,
                                                     final boolean stopOnError,
                                                     final QueryResultListener listener) {
        return execute(statements, stopOnError, listener, () -> { });
    }

    /**
     * 在任务提交到执行线程前同步发布执行已就绪状态。这样调用方可以先登记 executionId，
     * 同时保证此后到达的取消请求即使早于 Statement 创建也不会丢失。
     */
    public CompletableFuture<QueryExecution> execute(final List<SqlStatement> statements,
                                                     final boolean stopOnError,
                                                     final QueryResultListener listener,
                                                     final Runnable preparedCallback) {
        final List<SqlStatement> copied = Collections.unmodifiableList(new ArrayList<SqlStatement>(statements));
        final Map<String, String> loggingContext = MDC.getCopyOfContextMap();
        if (!executionActive.compareAndSet(false, true)) {
            throw new QueryExecutionException("当前已有 SQL 正在执行", null);
        }
        cancelRequested.set(false);
        try {
            preparedCallback.run();
        } catch (RuntimeException exception) {
            executionActive.set(false);
            throw exception;
        }
        return CompletableFuture.supplyAsync(() -> withLoggingContext(loggingContext,
                () -> executeBlocking(copied, stopOnError, listener)), executor)
                .whenComplete((ignored, failure) -> {
                    activeStatement.set(null);
                    executionActive.set(false);
                    cancelRequested.set(false);
                });
    }

    public CompletableFuture<Void> commit() {
        final Map<String, String> loggingContext = MDC.getCopyOfContextMap();
        return CompletableFuture.runAsync(() -> withLoggingContext(loggingContext, () -> {
            try {
                session.commit();
                transactionDirty.set(false);
                LOG.info("事务提交成功");
            } catch (SQLException exception) {
                LOG.warn("事务提交失败 sqlState={} errorCode={}", exception.getSQLState(), exception.getErrorCode(), exception);
                throw new QueryExecutionException("提交失败：" + exception.getMessage(), exception);
            }
            return null;
        }), executor);
    }

    public CompletableFuture<Void> rollback() {
        final Map<String, String> loggingContext = MDC.getCopyOfContextMap();
        return CompletableFuture.runAsync(() -> withLoggingContext(loggingContext, () -> {
            try {
                session.rollback();
                transactionDirty.set(false);
                LOG.info("事务回滚成功");
            } catch (SQLException exception) {
                LOG.warn("事务回滚失败 sqlState={} errorCode={}", exception.getSQLState(), exception.getErrorCode(), exception);
                throw new QueryExecutionException("回滚失败：" + exception.getMessage(), exception);
            }
            return null;
        }), executor);
    }

    /**
     * 在当前编辑器固定的 JDBC 会话上提交一批结果集单元格变更，但不提交事务。
     * 表名、列名和唯一键全部来自服务端解析出的结果元数据。
     */
    public CompletableFuture<List<RowChange>> applyResultChanges(final ResultMutationTarget target,
                                                                 final List<List<String>> rows,
                                                                 final List<RowChange> changes) {
        if (!executionActive.compareAndSet(false, true)) {
            throw new QueryExecutionException("当前已有数据库操作正在执行", null);
        }
        final Map<String, String> loggingContext = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> withLoggingContext(loggingContext,
                () -> applyResultChangesBlocking(target, rows, changes)), executor)
                .whenComplete((ignored, failure) -> executionActive.set(false));
    }

    private List<RowChange> applyResultChangesBlocking(ResultMutationTarget target,
                                                       List<List<String>> rows,
                                                       List<RowChange> changes) {
        if (target == null || !target.editableForUpdate()) {
            throw new QueryExecutionException("当前结果不支持直接修改", null);
        }
        Connection connection = session.jdbcConnection();
        Savepoint savepoint = null;
        try {
            if (connection.getAutoCommit()) {
                throw new QueryExecutionException("请关闭自动提交后重新执行 FOR UPDATE", null);
            }
            savepoint = connection.setSavepoint();
            List<RowChange> applied = new ArrayList<RowChange>();
            for (RowChange change : changes) {
                if (change.rowIndex() < 0 || change.rowIndex() >= rows.size()) {
                    throw new QueryExecutionException("结果行已经过期，请重新执行查询", null);
                }
                List<String> row = rows.get(change.rowIndex());
                List<CellChange> effective = effectiveCells(target, row, change.cells());
                if (effective.isEmpty()) continue;
                ResultMutationTarget.Key key = mutationKey(target, row);
                if (key == null) {
                    throw new QueryExecutionException("该行没有可用的非空唯一键，无法安全修改", null);
                }
                String sql = updateSql(target, key, effective);
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int parameter = 1;
                    for (CellChange cell : effective) {
                        bind(statement, parameter++, cell.value(), targetColumn(target, cell.columnIndex()).jdbcType());
                    }
                    for (Integer columnIndex : key.resultColumnIndices()) {
                        ResultMutationTarget.Column column = targetColumn(target, columnIndex);
                        bind(statement, parameter++, row.get(columnIndex), column.jdbcType());
                    }
                    int affected = statement.executeUpdate();
                    if (affected != 1) {
                        throw new QueryExecutionException("修改结果不唯一，已取消本批次修改", null);
                    }
                }
                applied.add(new RowChange(change.rowIndex(), effective));
            }
            releaseSavepoint(connection, savepoint);
            if (!applied.isEmpty()) transactionDirty.set(true);
            return Collections.unmodifiableList(applied);
        } catch (Exception exception) {
            if (savepoint != null) try { connection.rollback(savepoint); } catch (SQLException ignored) { }
            if (exception instanceof QueryExecutionException) throw (QueryExecutionException) exception;
            String detail = resultChangeFailureDetail(exception);
            throw new QueryExecutionException("确认结果修改失败：" + detail, exception);
        }
    }

    private static String resultChangeFailureDetail(Exception exception) {
        SQLException sqlException = exception instanceof SQLException
                ? (SQLException) exception : new SQLException(exception.getMessage(), exception);
        String detail = exception.getMessage() == null ? exception.getClass().getSimpleName()
                : sanitize(sqlException);
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        String state = sqlException.getSQLState();
        if (sqlException.getErrorCode() == 1265 || lower.contains("data truncated")) {
            return "字段值不符合数据库定义，请检查枚举可选值、长度或精度（" + detail + "）";
        }
        if (state != null && state.startsWith("22")) {
            return "字段值的格式或范围不符合数据库定义（" + detail + "）";
        }
        if (state != null && state.startsWith("23")) {
            return "字段值违反数据库约束（" + detail + "）";
        }
        return detail;
    }

    private static void releaseSavepoint(Connection connection, Savepoint savepoint) throws SQLException {
        if (savepoint == null) return;
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLFeatureNotSupportedException unsupported) {
            // Oracle JDBC keeps the savepoint until transaction end but does not implement releaseSavepoint.
            LOG.debug("JDBC 驱动不支持主动释放保存点，将由事务结束时清理");
        }
    }

    private static List<CellChange> effectiveCells(ResultMutationTarget target, List<String> row,
                                                   List<CellChange> cells) {
        List<CellChange> result = new ArrayList<CellChange>();
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (CellChange cell : cells) {
            ResultMutationTarget.Column column = targetColumn(target, cell.columnIndex());
            if (!seen.add(cell.columnIndex())) throw new QueryExecutionException("结果修改包含重复字段", null);
            if (!editableJdbcType(column.jdbcType())) {
                throw new QueryExecutionException("字段 " + column.name() + " 的类型不支持直接修改", null);
            }
            if (!Objects.equals(row.get(cell.columnIndex()), cell.value())) result.add(cell);
        }
        return result;
    }

    private static ResultMutationTarget.Column targetColumn(ResultMutationTarget target, int resultIndex) {
        for (ResultMutationTarget.Column column : target.columns()) {
            if (column.resultIndex() == resultIndex) return column;
        }
        throw new QueryExecutionException("结果字段不可修改或已经过期", null);
    }

    private static ResultMutationTarget.Key mutationKey(ResultMutationTarget target, List<String> row) {
        List<ResultMutationTarget.Key> keys = new ArrayList<ResultMutationTarget.Key>(target.uniqueKeys());
        Collections.sort(keys, (left, right) -> Boolean.compare(right.primary(), left.primary()));
        for (ResultMutationTarget.Key key : keys) {
            boolean usable = !key.resultColumnIndices().isEmpty();
            for (Integer index : key.resultColumnIndices()) {
                if (index < 0 || index >= row.size() || row.get(index) == null) { usable = false; break; }
            }
            if (usable) return key;
        }
        return null;
    }

    private static String updateSql(ResultMutationTarget target, ResultMutationTarget.Key key,
                                    List<CellChange> cells) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(target.qualifiedName()).append(" SET ");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(targetColumn(target, cells.get(index).columnIndex()).quotedName()).append(" = ?");
        }
        sql.append(" WHERE ");
        for (int index = 0; index < key.resultColumnIndices().size(); index++) {
            if (index > 0) sql.append(" AND ");
            sql.append(targetColumn(target, key.resultColumnIndices().get(index)).quotedName()).append(" = ?");
        }
        return sql.toString();
    }

    public static boolean editableJdbcType(int jdbcType) {
        switch (jdbcType) {
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
            case Types.BLOB:
            case Types.CLOB:
            case Types.NCLOB:
            case Types.ARRAY:
            case Types.STRUCT:
            case Types.REF:
            case Types.ROWID:
            case Types.SQLXML:
            case Types.JAVA_OBJECT:
            case Types.OTHER:
                return false;
            default:
                return true;
        }
    }

    private static void bind(PreparedStatement statement, int index, String value, int jdbcType)
            throws SQLException {
        if (value == null) { statement.setNull(index, jdbcType); return; }
        try {
            switch (jdbcType) {
                case Types.TINYINT:
                case Types.SMALLINT:
                case Types.INTEGER:
                case Types.BIGINT:
                case Types.FLOAT:
                case Types.REAL:
                case Types.DOUBLE:
                case Types.NUMERIC:
                case Types.DECIMAL:
                    statement.setBigDecimal(index, new BigDecimal(value)); return;
                case Types.BOOLEAN:
                case Types.BIT:
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)
                            && !"1".equals(value) && !"0".equals(value)) {
                        throw new IllegalArgumentException("布尔值必须为 true、false、1 或 0");
                    }
                    statement.setBoolean(index, "true".equalsIgnoreCase(value) || "1".equals(value)); return;
                case Types.DATE:
                    statement.setDate(index, java.sql.Date.valueOf(value)); return;
                case Types.TIME:
                case Types.TIME_WITH_TIMEZONE:
                    statement.setTime(index, java.sql.Time.valueOf(value)); return;
                case Types.TIMESTAMP:
                case Types.TIMESTAMP_WITH_TIMEZONE:
                    statement.setTimestamp(index, java.sql.Timestamp.valueOf(value.replace('T', ' '))); return;
                default:
                    statement.setObject(index, value, jdbcType);
            }
        } catch (IllegalArgumentException exception) {
            throw new SQLException("值格式与字段类型不匹配：" + exception.getMessage(), exception);
        }
    }

    public static final class CellChange {
        private final int columnIndex;
        private final String value;
        public CellChange(int columnIndex, String value) {
            this.columnIndex = columnIndex;
            this.value = value;
        }
        public int columnIndex() { return columnIndex; }
        public String value() { return value; }
    }

    public static final class RowChange {
        private final int rowIndex;
        private final List<CellChange> cells;
        public RowChange(int rowIndex, List<CellChange> cells) {
            this.rowIndex = rowIndex;
            this.cells = Collections.unmodifiableList(new ArrayList<CellChange>(cells));
        }
        public int rowIndex() { return rowIndex; }
        public List<CellChange> cells() { return cells; }
    }

    /**
     * 在当前编辑器 JDBC 会话上重新执行只读结果并返回一个分页窗口。
     * 任务仍提交到编辑器专属执行器，以保持连接顺序和事务隔离级别。
     */
    public CompletableFuture<PageResult> fetchPage(final String sql, final int offset, final int limit) {
        return fetchPage(sql, offset, limit, () -> { });
    }

    /**
     * 在分页任务进入执行器前同步登记运行状态，确保调用方可以发布任务编号，并接受早于
     * JDBC Statement 创建的取消请求。
     */
    public CompletableFuture<PageResult> fetchPage(final String sql, final int offset, final int limit,
                                                   final Runnable preparedCallback) {
        if (offset < 0) throw new IllegalArgumentException("offset must not be negative");
        if (limit < 1) throw new IllegalArgumentException("limit must be positive");
        if (!executionActive.compareAndSet(false, true)) {
            throw new QueryExecutionException("当前已有 SQL 正在执行", null);
        }
        cancelRequested.set(false);
        try {
            preparedCallback.run();
        } catch (RuntimeException exception) {
            executionActive.set(false);
            throw exception;
        }
        final Map<String, String> loggingContext = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> withLoggingContext(loggingContext,
                () -> fetchPageBlocking(sql, offset, limit)), executor)
                .whenComplete((ignored, failure) -> {
                    activeStatement.set(null);
                    executionActive.set(false);
                    cancelRequested.set(false);
                });
    }

    public boolean cancel() {
        if (!executionActive.get() && activeStatement.get() == null) return false;
        cancelRequested.set(true);
        Statement statement = activeStatement.get();
        if (statement == null) {
            LOG.info("SQL取消请求已登记，等待Statement创建");
            return true;
        }
        try {
            statement.cancel();
            LOG.info("取消当前SQL执行");
            return true;
        } catch (SQLException exception) {
            LOG.warn("取消SQL执行失败", exception);
            throw new QueryExecutionException("取消执行失败：" + exception.getMessage(), exception);
        }
    }

    public boolean isRunning() { return executionActive.get() || activeStatement.get() != null; }
    public boolean isTransactionDirty() { return transactionDirty.get(); }
    public void setMaxRows(int maxRows) { this.maxRows = Math.max(1, maxRows); }
    public void setStreamBatchRows(int streamBatchRows) { this.streamBatchRows = Math.max(1, streamBatchRows); }

    private PageResult fetchPageBlocking(String sql, int offset, int limit) {
        Instant started = Instant.now();
        LOG.info("分页查询开始 offset={} limit={} {}", offset, limit, SqlLogSupport.summary(sql));
        if (cancelRequested.get()) return PageResult.cancelledResult();
        try (Statement statement = session.jdbcConnection().createStatement()) {
            statement.setFetchSize(JDBC_FETCH_SIZE);
            activeStatement.set(statement);
            if (cancelRequested.get()) return PageResult.cancelledResult();
            if (!statement.execute(sql)) {
                throw new QueryExecutionException("该结果不是可分页的查询结果", null);
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                int skipped = 0;
                while (skipped < offset && !cancelRequested.get() && resultSet.next()) skipped++;
                if (cancelRequested.get()) return PageResult.cancelledResult();
                if (skipped < offset) return new PageResult(Collections.<List<String>>emptyList(), false);

                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<List<String>> rows = new ArrayList<List<String>>(limit);
                while (rows.size() < limit && !cancelRequested.get() && resultSet.next()) {
                    List<String> row = new ArrayList<String>(columnCount);
                    for (int index = 1; index <= columnCount; index++) {
                        row.add(displayValue(resultSet.getObject(index)));
                    }
                    rows.add(Collections.unmodifiableList(row));
                }
                if (cancelRequested.get()) return PageResult.cancelledResult();
                boolean hasMore = resultSet.next();
                if (cancelRequested.get()) return PageResult.cancelledResult();
                PageResult result = new PageResult(rows, hasMore);
                LOG.info("分页查询完成 rows={} hasMore={} durationMs={}", rows.size(), hasMore,
                        Duration.between(started, Instant.now()).toMillis());
                return result;
            }
        } catch (SQLException exception) {
            if (cancelRequested.get()) {
                LOG.info("分页查询已取消 durationMs={}", Duration.between(started, Instant.now()).toMillis());
                return PageResult.cancelledResult();
            }
            LOG.warn("分页查询失败 sqlState={} errorCode={} durationMs={}", exception.getSQLState(),
                    exception.getErrorCode(), Duration.between(started, Instant.now()).toMillis(), exception);
            throw new QueryExecutionException("加载更多结果失败：" + sanitize(exception), exception);
        } finally {
            activeStatement.set(null);
        }
    }

    private QueryExecution executeBlocking(List<SqlStatement> statements, boolean stopOnError,
                                           QueryResultListener listener) {
        Instant started = Instant.now();
        LOG.info("SQL批次开始 statements={} stopOnError={} maxRows={} streamBatchRows={}",
                statements.size(), stopOnError, maxRows, streamBatchRows);
        List<StatementResult> results = new ArrayList<StatementResult>();
        boolean cancelled = false;
        for (SqlStatement sqlStatement : statements) {
            if (cancelRequested.get() || Thread.currentThread().isInterrupted()) {
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
                cancelled = cancelRequested.get() || isCancellation(failure.errorMessage());
                if (stopOnError) break;
            }
        }
        cancelled = cancelled || cancelRequested.get();
        QueryExecution execution = new QueryExecution(results, Duration.between(started, Instant.now()), cancelled);
        LOG.info("SQL批次完成 statements={} results={} cancelled={} failed={} affectedRows={} durationMs={}",
                statements.size(), results.size(), cancelled, execution.failed(), execution.affectedRows(),
                execution.duration().toMillis());
        return execution;
    }

    private List<StatementResult> executeOne(SqlStatement sqlStatement, int firstResultIndex,
                                             QueryResultListener listener) {
        Instant started = Instant.now();
        final int statementMaxRows = maxRows;
        final int statementBatchRows = streamBatchRows;
        LOG.info("SQL语句开始 index={} type={} {}", firstResultIndex, sqlStatement.type(),
                SqlLogSupport.summary(sqlStatement.text()));
        String preview = SqlLogSupport.preview(sqlStatement.text());
        if (!preview.isEmpty()) LOG.debug("SQL语句预览 index={} text={}", firstResultIndex, preview);
        if (cancelRequested.get()) return Collections.emptyList();
        try (Statement statement = session.jdbcConnection().createStatement()) {
            statement.setFetchSize(JDBC_FETCH_SIZE);
            statement.setMaxRows(statementMaxRows + 1);
            activeStatement.set(statement);
            if (cancelRequested.get()) return Collections.emptyList();
            boolean hasResult = statement.execute(sqlStatement.text());
            TransactionEffect effect = updateTransactionState(sqlStatement, hasResult);
            if (effect == TransactionEffect.IMPLICIT_COMMIT) {
                columnResolver.invalidate();
            }

            List<StatementResult> outputs = new ArrayList<StatementResult>();
            while (true) {
                int resultIndex = firstResultIndex + outputs.size();
                StatementResult output;
                if (hasResult) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        output = readResultSet(sqlStatement, resultSet, started, resultIndex, listener,
                                statementMaxRows, statementBatchRows);
                    }
                } else {
                    int updateCount = statement.getUpdateCount();
                    if (updateCount == -1) break;
                    output = new StatementResult(sqlStatement.text(), sqlStatement.type(),
                            Collections.<String>emptyList(), Collections.<List<String>>emptyList(),
                            updateCount, false, Duration.between(started, Instant.now()), null);
                    listener.resultMetadata(resultIndex, sqlStatement.text(), sqlStatement.type(), output.columnDetails());
                }
                outputs.add(output);
                listener.resultCompleted(resultIndex, output);
                if (cancelRequested.get()) break;
                hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }
            if (outputs.isEmpty()) {
                StatementResult output = new StatementResult(sqlStatement.text(), sqlStatement.type(),
                        Collections.<String>emptyList(), Collections.<List<String>>emptyList(),
                        0, false, Duration.between(started, Instant.now()), null);
                listener.resultMetadata(firstResultIndex, sqlStatement.text(), sqlStatement.type(), output.columnDetails());
                listener.resultCompleted(firstResultIndex, output);
                outputs.add(output);
            }
            LOG.info("SQL语句完成 index={} results={} durationMs={} transactionEffect={}", firstResultIndex,
                    outputs.size(), Duration.between(started, Instant.now()).toMillis(), effect);
            return outputs;
        } catch (SQLException exception) {
            LOG.warn("SQL语句失败 index={} sqlState={} errorCode={} durationMs={} message={}", firstResultIndex,
                    exception.getSQLState(), exception.getErrorCode(),
                    Duration.between(started, Instant.now()).toMillis(), sanitize(exception), exception);
            StatementResult failure = new StatementResult(sqlStatement.text(), sqlStatement.type(),
                    Collections.<String>emptyList(), Collections.<List<String>>emptyList(), -1, false,
                    Duration.between(started, Instant.now()), sanitize(exception));
            listener.resultMetadata(firstResultIndex, sqlStatement.text(), sqlStatement.type(), failure.columnDetails());
            listener.resultCompleted(firstResultIndex, failure);
            return Collections.singletonList(failure);
        } finally {
            activeStatement.set(null);
        }
    }

    private StatementResult readResultSet(SqlStatement sqlStatement, ResultSet resultSet, Instant started,
                                          int resultIndex, QueryResultListener listener,
                                          int resultMaxRows, int resultBatchRows) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columns = new ArrayList<String>(columnCount);
        List<ResultColumn> columnDetails = new ArrayList<ResultColumn>(columnCount);
        for (int index = 1; index <= columnCount; index++) {
            String label = metadata.getColumnLabel(index);
            String name = metadata.getColumnName(index);
            String display = label == null || label.trim().isEmpty() ? name : label;
            columns.add(display);
            columnDetails.add(new ResultColumn(display, name, metadata.getCatalogName(index),
                    metadata.getSchemaName(index), metadata.getTableName(index), metadata.getColumnTypeName(index), "",
                    metadata.getColumnType(index), display));
        }
        ResolvedResultMetadata resolved = columnResolver.resolve(sqlStatement.text(),
                Collections.unmodifiableList(columnDetails));
        columnDetails = resolved.columns();
        listener.resultMetadata(resultIndex, sqlStatement.text(), sqlStatement.type(), columnDetails,
                resolved.mutationTarget());

        List<List<String>> rows = new ArrayList<List<String>>(Math.min(resultMaxRows, JDBC_FETCH_SIZE));
        List<List<String>> batch = new ArrayList<List<String>>(Math.min(resultBatchRows, resultMaxRows));
        boolean truncated = false;
        while (!cancelRequested.get() && resultSet.next()) {
            if (rows.size() >= resultMaxRows) { truncated = true; break; }
            List<String> row = new ArrayList<String>(columnCount);
            for (int index = 1; index <= columnCount; index++) row.add(displayValue(resultSet.getObject(index)));
            rows.add(row);
            batch.add(Collections.unmodifiableList(new ArrayList<String>(row)));
            if (batch.size() == resultBatchRows) {
                listener.rows(resultIndex, immutableRows(batch));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) listener.rows(resultIndex, immutableRows(batch));
        return new StatementResult(sqlStatement.text(), sqlStatement.type(), columns, columnDetails,
                resolved.mutationTarget(), rows, -1, truncated, Duration.between(started, Instant.now()), null);
    }

    private static List<List<String>> immutableRows(List<List<String>> rows) {
        List<List<String>> copied = new ArrayList<List<String>>(rows.size());
        for (List<String> row : rows) copied.add(Collections.unmodifiableList(new ArrayList<String>(row)));
        return Collections.unmodifiableList(copied);
    }

    public static final class PageResult {
        private final List<List<String>> rows;
        private final boolean hasMore;
        private final boolean cancelled;

        private PageResult(List<List<String>> rows, boolean hasMore) {
            this(rows, hasMore, false);
        }

        private PageResult(List<List<String>> rows, boolean hasMore, boolean cancelled) {
            this.rows = immutableRows(rows);
            this.hasMore = hasMore;
            this.cancelled = cancelled;
        }

        private static PageResult cancelledResult() {
            return new PageResult(Collections.<List<String>>emptyList(), true, true);
        }

        public List<List<String>> rows() { return rows; }
        public boolean hasMore() { return hasMore; }
        public boolean cancelled() { return cancelled; }
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
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName()
                : SqlLogSupport.sanitizeMessage(message);
    }

    /**
     * 异步执行器不会自动继承 servlet 线程的 MDC，这里显式复制并在任务结束后恢复，避免
     * requestId 泄漏到下一个 JDBC 任务，同时让查询日志可以按请求维度串联。
     */
    private static <T> T withLoggingContext(Map<String, String> context, Supplier<T> action) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            MDC.clear();
            if (context != null) MDC.setContextMap(context);
            return action.get();
        } finally {
            MDC.clear();
            if (previous != null) MDC.setContextMap(previous);
        }
    }

    private static boolean isCancellation(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("cancel") || lower.contains("interrupt");
    }

    private TransactionEffect updateTransactionState(SqlStatement statement, boolean hasResult) throws SQLException {
        TransactionEffect effect = dialect == null
                ? defaultTransactionEffect(statement, hasResult)
                : dialect.transactionEffect(statement, hasResult);
        boolean autoCommit = session.jdbcConnection().getAutoCommit();
        if (autoCommit) {
            transactionDirty.set(false);
        } else if (effect == TransactionEffect.DIRTY) {
            transactionDirty.set(true);
        } else if (effect == TransactionEffect.END || effect == TransactionEffect.IMPLICIT_COMMIT) {
            transactionDirty.set(false);
        }
        LOG.debug("事务状态更新 statementType={} hasResult={} effect={} autoCommit={} dirty={}",
                statement.type(), hasResult, effect, autoCommit, transactionDirty.get());
        return effect;
    }

    private static TransactionEffect defaultTransactionEffect(SqlStatement statement, boolean hasResult) {
        StatementType type = statement.type();
        if (type.modifiesData() || type == StatementType.OTHER) return TransactionEffect.DIRTY;
        if (type == StatementType.DDL) return TransactionEffect.IMPLICIT_COMMIT;
        if (type == StatementType.TRANSACTION) {
            String upper = statement.text().trim().toUpperCase(java.util.Locale.ROOT);
            return upper.startsWith("COMMIT") || upper.startsWith("ROLLBACK")
                    ? TransactionEffect.END : TransactionEffect.DIRTY;
        }
        return type == StatementType.QUERY && !hasResult ? TransactionEffect.DIRTY : TransactionEffect.NONE;
    }

    @Override
    public void close() {
        try { cancel(); } catch (RuntimeException exception) { LOG.debug("关闭执行器时取消SQL失败", exception); }
        executor.shutdownNow();
        if (transactionDirty.getAndSet(false)) {
            try { session.rollback(); } catch (SQLException exception) { LOG.warn("关闭执行器时回滚失败", exception); }
        }
        if (ownsSession) try { session.close(); }
        catch (SQLException exception) { LOG.warn("关闭JDBC会话失败", exception); }
        LOG.debug("SQL执行器已关闭 ownsSession={}", ownsSession);
    }

    public static final class QueryExecutionException extends RuntimeException {
        public QueryExecutionException(String message, Throwable cause) { super(message, cause); }
    }
}
