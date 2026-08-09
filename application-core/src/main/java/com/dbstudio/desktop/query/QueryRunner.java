package com.dbstudio.desktop.query;

import com.dbstudio.desktop.logging.SqlLogSupport;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.TransactionEffect;
import com.dbstudio.spi.SqlLogCategory;
import com.dbstudio.spi.SqlLogging;
import java.io.IOException;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Blob;
import java.sql.CallableStatement;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Base64;
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
                () -> withSqlCategory(SqlLogCategory.RESULT_EDIT,
                        () -> applyResultChangesBlocking(target, rows, changes))), executor)
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

    /** Applies an ordered, atomic batch of update/insert/delete operations without committing the transaction. */
    public CompletableFuture<MutationBatchResult> applyResultOperations(final ResultMutationTarget target,
                                                                         final List<List<String>> rows,
                                                                         final List<ResultOperation> operations) {
        return applyResultOperations(target, rows, emptyLocators(rows == null ? 0 : rows.size()), operations);
    }

    public CompletableFuture<MutationBatchResult> applyResultOperations(final ResultMutationTarget target,
                                                                         final List<List<String>> rows,
                                                                         final List<List<String>> rowLocators,
                                                                         final List<ResultOperation> operations) {
        if (!executionActive.compareAndSet(false, true)) {
            throw new QueryExecutionException("当前已有数据库操作正在执行", null);
        }
        final Map<String, String> loggingContext = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> withLoggingContext(loggingContext,
                () -> withSqlCategory(SqlLogCategory.RESULT_EDIT,
                        () -> applyResultOperationsBlocking(target, rows, rowLocators, operations))), executor)
                .whenComplete((ignored, failure) -> executionActive.set(false));
    }

    /** Streams one RAW/CLOB/BLOB value from the pinned result transaction without materializing it in memory. */
    public CompletableFuture<Long> streamResultValue(final ResultMutationTarget target,
                                                      final List<String> row,
                                                      final List<String> rowLocator,
                                                      final int columnIndex,
                                                      final OutputStream output,
                                                      final long maximumBytes) {
        if (!executionActive.compareAndSet(false, true)) {
            throw new QueryExecutionException("当前已有数据库操作正在执行", null);
        }
        final Map<String, String> loggingContext = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> withLoggingContext(loggingContext,
                () -> withSqlCategory(SqlLogCategory.RESULT_EDIT, () -> {
            try {
                return streamResultValueBlocking(target, row, rowLocator, columnIndex, output, maximumBytes);
            } catch (SQLException | IOException exception) {
                throw new QueryExecutionException("读取大字段失败：" + resultChangeFailureDetail(exception), exception);
            }
        })), executor).whenComplete((ignored, failure) -> {
            activeStatement.set(null);
            executionActive.set(false);
        });
    }

    private long streamResultValueBlocking(ResultMutationTarget target, List<String> row,
                                           List<String> rowLocator, int columnIndex,
                                           OutputStream output, long maximumBytes)
            throws SQLException, IOException {
        ResultMutationTarget.Column column = targetColumn(target, columnIndex);
        if (!("raw".equals(column.typeFamily()) || "blob".equals(column.typeFamily())
                || "clob".equals(column.typeFamily()))) {
            throw new QueryExecutionException("该字段不是可流式读取的大字段", null, null, columnIndex);
        }
        if (column.quotedName() == null || column.quotedName().isEmpty()) {
            throw new QueryExecutionException("表达式字段无法读取原始大字段", null, null, columnIndex);
        }
        OperationLocator locator = operationLocator(target, row, rowLocator);
        if (locator == null) throw new QueryExecutionException("该行没有可用的安全定位器", null);
        StringBuilder sql = new StringBuilder("SELECT ").append(column.quotedName())
                .append(" FROM ").append(target.qualifiedName());
        appendLocatorPredicate(sql, locator);
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql.toString())) {
            activeStatement.set(statement);
            bindLocator(statement, 1, locator);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new QueryExecutionException("目标行已经失效", null);
                LimitedOutputStream limited = new LimitedOutputStream(output, maximumBytes);
                if ("clob".equals(column.typeFamily())) {
                    try (Reader reader = result.getCharacterStream(1)) {
                        if (reader != null) {
                            java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(limited, StandardCharsets.UTF_8);
                            char[] buffer = new char[8192]; int count;
                            while ((count = reader.read(buffer)) >= 0) writer.write(buffer, 0, count);
                            writer.flush();
                        }
                    }
                } else {
                    try (InputStream input = result.getBinaryStream(1)) {
                        if (input != null) {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = input.read(buffer)) >= 0) limited.write(buffer, 0, count);
                        }
                    }
                }
                if (result.next()) throw new QueryExecutionException("目标行定位结果不唯一", null);
                return limited.written();
            }
        }
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long maximum;
        private long written;
        private LimitedOutputStream(OutputStream delegate, long maximum) {
            this.delegate = delegate; this.maximum = Math.max(1L, maximum);
        }
        @Override public void write(int value) throws IOException {
            ensure(1); delegate.write(value); written++;
        }
        @Override public void write(byte[] values, int offset, int length) throws IOException {
            ensure(length); delegate.write(values, offset, length); written += length;
        }
        private void ensure(int length) throws IOException {
            if (written + length > maximum) throw new IOException("大字段超过允许的最大大小");
        }
        private long written() { return written; }
    }

    private MutationBatchResult applyResultOperationsBlocking(ResultMutationTarget target,
                                                                List<List<String>> rows,
                                                                List<List<String>> rowLocators,
                                                                List<ResultOperation> operations) {
        if (target == null || !target.editableForUpdate()) {
            throw new QueryExecutionException(target == null || target.reason().isEmpty()
                    ? "当前结果不支持直接修改" : target.reason(), null);
        }
        if (operations == null || operations.isEmpty()) {
            throw new QueryExecutionException("没有需要应用的结果修改", null);
        }
        Connection connection = session.jdbcConnection();
        Savepoint savepoint = null;
        try {
            if (connection.getAutoCommit()) {
                throw new QueryExecutionException("请关闭自动提交后重新执行 FOR UPDATE", null);
            }
            savepoint = connection.setSavepoint();
            List<OperationResult> applied = new ArrayList<OperationResult>();
            for (ResultOperation operation : operations) {
                try {
                    if (operation.kind() == MutationKind.UPDATE) {
                        applied.add(applyUpdateOperation(connection, target, rows, rowLocators, operation));
                    } else if (operation.kind() == MutationKind.DELETE) {
                        applied.add(applyDeleteOperation(connection, target, rows, rowLocators, operation));
                    } else if (operation.kind() == MutationKind.INSERT) {
                        applied.add(applyInsertOperation(connection, target, operation));
                    } else {
                        throw new QueryExecutionException("未知的结果修改操作", null);
                    }
                } catch (QueryExecutionException exception) {
                    throw exception.withOperation(operation.operationId());
                } catch (Exception exception) {
                    throw new QueryExecutionException("应用结果修改失败：" + resultChangeFailureDetail(exception),
                            exception, operation.operationId(), errorColumnIndex(target, exception));
                }
            }
            releaseSavepoint(connection, savepoint);
            transactionDirty.set(true);
            return new MutationBatchResult(applied);
        } catch (Exception exception) {
            if (savepoint != null) try { connection.rollback(savepoint); } catch (SQLException ignored) { }
            if (exception instanceof QueryExecutionException) throw (QueryExecutionException) exception;
            throw new QueryExecutionException("应用结果修改失败：" + resultChangeFailureDetail(exception), exception);
        }
    }

    private OperationResult applyUpdateOperation(Connection connection, ResultMutationTarget target,
                                                   List<List<String>> rows, List<List<String>> rowLocators,
                                                   ResultOperation operation)
            throws SQLException {
        List<String> row = sourceRow(rows, operation.rowIndex());
        List<String> sourceLocator = sourceLocator(rowLocators, operation.rowIndex());
        List<ValueChange> cells = validatedValues(target, operation.values(), false);
        List<ValueChange> effective = new ArrayList<ValueChange>();
        for (ValueChange cell : cells) {
            String next = cell.value().asDisplayValue();
            if (cell.value().isDefault() || !Objects.equals(row.get(cell.columnIndex()), next)) effective.add(cell);
        }
        if (effective.isEmpty()) return new OperationResult(operation.operationId(), operation.kind(),
                operation.rowIndex(), row, sourceLocator, Collections.<ValueChange>emptyList(), true);
        OperationLocator locator = operationLocator(target, row, sourceLocator);
        if (locator == null) throw new QueryExecutionException("该行没有可用的安全定位器，无法修改", null);
        String sql = updateOperationSql(target, locator, effective);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            List<Closeable> resources = new ArrayList<Closeable>();
            try {
                int parameter = 1;
                for (ValueChange cell : effective) {
                    if (!cell.value().isDefault()) addResource(resources,
                            bindOperationValue(statement, parameter++, cell, target));
                }
                bindLocator(statement, parameter, locator);
                requireSingleRow(statement.executeUpdate(), "更新");
            } finally {
                closeResources(resources);
            }
        }
        List<String> updated = new ArrayList<String>(row);
        for (ValueChange cell : effective) updated.set(cell.columnIndex(), cell.value().asDisplayValue());
        List<String> nextLocatorValues = updatedLocatorValues(target, sourceLocator, updated);
        OperationLocator nextLocator = operationLocator(target, updated, nextLocatorValues);
        if (nextLocator == null) throw new QueryExecutionException("修改定位字段后无法重新定位该行", null);
        List<String> authoritative = refreshResultRow(connection, target, nextLocator, updated);
        return new OperationResult(operation.operationId(), operation.kind(), operation.rowIndex(),
                authoritative == null ? Collections.<String>emptyList() : authoritative,
                nextLocatorValues, effective, authoritative != null);
    }

    private OperationResult applyDeleteOperation(Connection connection, ResultMutationTarget target,
                                                   List<List<String>> rows, List<List<String>> rowLocators,
                                                   ResultOperation operation)
            throws SQLException {
        if (!target.deleteSupported()) throw new QueryExecutionException("当前结果不支持删除记录", null);
        List<String> row = sourceRow(rows, operation.rowIndex());
        List<String> sourceLocator = sourceLocator(rowLocators, operation.rowIndex());
        OperationLocator locator = operationLocator(target, row, sourceLocator);
        if (locator == null) throw new QueryExecutionException("该行没有可用的安全定位器，无法删除", null);
        try (PreparedStatement statement = connection.prepareStatement(deleteSql(target, locator))) {
            bindLocator(statement, 1, locator);
            requireSingleRow(statement.executeUpdate(), "删除");
        }
        return new OperationResult(operation.operationId(), operation.kind(), operation.rowIndex(), row,
                sourceLocator, Collections.<ValueChange>emptyList(), true);
    }

    private OperationResult applyInsertOperation(Connection connection, ResultMutationTarget target,
                                                   ResultOperation operation) throws SQLException {
        if (!target.insertSupported()) throw new QueryExecutionException("当前结果不支持新增记录", null);
        List<ValueChange> cells = validatedValues(target, operation.values(), true);
        if (cells.isEmpty()) throw new QueryExecutionException("新增记录至少需要设置一个字段或 DEFAULT", null);
        String sql = insertSql(target, cells);
        if (target.locator() != null && "ROWID".equals(target.locator().kind())) {
            return applyRowIdInsert(connection, target, operation, cells, sql);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            List<Closeable> resources = new ArrayList<Closeable>();
            try {
                int parameter = 1;
                for (ValueChange cell : cells) {
                    if (!cell.value().isDefault()) addResource(resources,
                            bindOperationValue(statement, parameter++, cell, target));
                }
                requireSingleRow(statement.executeUpdate(), "新增");
                List<String> row = new ArrayList<String>(Collections.nCopies(visibleColumnCount(target), null));
                for (ValueChange cell : cells) {
                    if (!cell.value().isDefault()) row.set(cell.columnIndex(), cell.value().asDisplayValue());
                }
                String generatedValue = null;
                try (ResultSet generated = statement.getGeneratedKeys()) {
                    if (generated != null && generated.next()) {
                        generatedValue = displayValue(generated.getObject(1));
                        ResultMutationTarget.Column generatedColumn = firstAutoIncrementColumn(target);
                        if (generatedColumn != null) row.set(generatedColumn.resultIndex(), generatedValue);
                    }
                }
                List<String> locatorValues = updatedLocatorValues(target, Collections.<String>emptyList(), row);
                if ((locatorValues.isEmpty() || containsNull(locatorValues)) && generatedValue != null
                        && target.locator() != null && target.locator().predicates().size() == 1) {
                    locatorValues = Collections.singletonList(generatedValue);
                }
                OperationLocator locator = operationLocator(target, row, locatorValues);
                if (locator == null) {
                    throw new QueryExecutionException("新增记录后无法取得安全行标识，请显式填写主键或唯一键", null);
                }
                List<String> authoritative = refreshResultRow(connection, target, locator, row);
                return new OperationResult(operation.operationId(), operation.kind(), -1,
                        authoritative == null ? Collections.<String>emptyList() : authoritative,
                        locator.values, cells, authoritative != null);
            } finally {
                closeResources(resources);
            }
        }
    }

    private OperationResult applyRowIdInsert(Connection connection, ResultMutationTarget target,
                                               ResultOperation operation, List<ValueChange> cells,
                                               String insertSql) throws SQLException {
        String block = "BEGIN " + insertSql + " RETURNING ROWID INTO ?; END;";
        try (CallableStatement statement = connection.prepareCall(block)) {
            List<Closeable> resources = new ArrayList<Closeable>();
            try {
                int parameter = 1;
                for (ValueChange cell : cells) {
                    if (!cell.value().isDefault()) addResource(resources,
                            bindOperationValue(statement, parameter++, cell, target));
                }
                statement.registerOutParameter(parameter, Types.VARCHAR);
                statement.execute();
                List<String> locatorValues = Collections.singletonList(statement.getString(parameter));
                OperationLocator locator = operationLocator(target, Collections.<String>emptyList(), locatorValues);
                if (locator == null) throw new QueryExecutionException("新增记录后无法取得 ROWID", null);
                List<String> row = new ArrayList<String>(Collections.nCopies(visibleColumnCount(target), null));
                for (ValueChange cell : cells) if (!cell.value().isDefault()) {
                    row.set(cell.columnIndex(), cell.value().asDisplayValue());
                }
                List<String> authoritative = refreshResultRow(connection, target, locator, row);
                return new OperationResult(operation.operationId(), operation.kind(), -1,
                        authoritative == null ? Collections.<String>emptyList() : authoritative,
                        locatorValues, cells, authoritative != null);
            } finally {
                closeResources(resources);
            }
        }
    }

    private static List<String> sourceRow(List<List<String>> rows, int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            throw new QueryExecutionException("结果行已经过期，请重新执行查询", null);
        }
        return rows.get(rowIndex);
    }

    private static List<String> sourceLocator(List<List<String>> locators, int rowIndex) {
        if (locators == null || rowIndex < 0 || rowIndex >= locators.size()) {
            return Collections.emptyList();
        }
        return locators.get(rowIndex);
    }

    private static List<ValueChange> validatedValues(ResultMutationTarget target, List<ValueChange> cells,
                                                       boolean inserting) {
        if (cells == null || cells.isEmpty()) return Collections.emptyList();
        List<ValueChange> result = new ArrayList<ValueChange>();
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        for (ValueChange cell : cells) {
            ResultMutationTarget.Column column = targetColumn(target, cell.columnIndex());
            if (!seen.add(cell.columnIndex())) throw new QueryExecutionException("结果修改包含重复字段", null);
            if (!column.editable() && !(inserting && column.autoIncrement() && cell.value().isDefault())) {
                throw new QueryExecutionException("字段 " + column.name() + "：" + column.readOnlyReason(), null,
                        null, column.resultIndex());
            }
            if (cell.value().isDefault() && !inserting && !column.defaultAvailable()) {
                throw new QueryExecutionException("字段 " + column.name() + " 没有可用默认值", null,
                        null, column.resultIndex());
            }
            if (cell.value().isNull() && !column.nullable()) {
                throw new QueryExecutionException("字段 " + column.name() + " 不允许为空", null,
                        null, column.resultIndex());
            }
            validateTextValue(target, column, cell.value());
            result.add(cell);
        }
        return result;
    }

    private static void validateTextValue(ResultMutationTarget target, ResultMutationTarget.Column column,
                                          MutationValue value) {
        if (value.isNull() || value.isDefault() || value.isLargeValueFile() || value.isLargeValueToken()) return;
        String text = value.value();
        if (target.emptyStringIsNull() && text.isEmpty() && !column.nullable()) {
            throw new QueryExecutionException("字段 " + column.name()
                    + " 在 Oracle 兼容模式下不能使用空字符串（会转换为 NULL）", null,
                    null, column.resultIndex());
        }
        if (!column.enumValues().isEmpty() && !column.enumValues().contains(text)) {
            throw new QueryExecutionException("字段 " + column.name() + " 的枚举可选值为 "
                    + String.join("、", column.enumValues()), null, null, column.resultIndex());
        }
        if ("text".equals(column.typeFamily()) && column.size() > 0
                && text.codePointCount(0, text.length()) > column.size()) {
            throw new QueryExecutionException("字段 " + column.name() + " 最多允许 "
                    + column.size() + " 个字符", null, null, column.resultIndex());
        }
        if ("number".equals(column.typeFamily()) && !text.isEmpty()) {
            try {
                BigDecimal number = new BigDecimal(text);
                if (column.scale() >= 0 && number.scale() > column.scale()) {
                    throw new QueryExecutionException("字段 " + column.name() + " 最多允许 "
                            + column.scale() + " 位小数", null, null, column.resultIndex());
                }
                if (column.size() > 0 && number.precision() > column.size()) {
                    throw new QueryExecutionException("字段 " + column.name() + " 超过允许精度 "
                            + column.size(), null, null, column.resultIndex());
                }
            } catch (NumberFormatException exception) {
                throw new QueryExecutionException("字段 " + column.name() + " 必须是有效数值",
                        exception, null, column.resultIndex());
            }
        }
    }

    private static Integer errorColumnIndex(ResultMutationTarget target, Exception exception) {
        String message = String.valueOf(exception.getMessage()).toLowerCase(java.util.Locale.ROOT);
        for (ResultMutationTarget.Column column : target.columns()) {
            if (!column.name().isEmpty()
                    && message.contains(column.name().toLowerCase(java.util.Locale.ROOT))) {
                return column.resultIndex();
            }
        }
        return null;
    }

    private static void requireSingleRow(int affected, String action) {
        if (affected != 1) throw new QueryExecutionException(action + "结果不唯一，已取消本批次修改", null);
    }

    private static int visibleColumnCount(ResultMutationTarget target) {
        int count = 0;
        for (ResultMutationTarget.Column column : target.columns()) count = Math.max(count, column.resultIndex() + 1);
        return count;
    }

    private static ResultMutationTarget.Column firstAutoIncrementColumn(ResultMutationTarget target) {
        for (ResultMutationTarget.Column column : target.columns()) if (column.autoIncrement()) return column;
        return null;
    }

    public static List<MutationPreview> previewResultOperations(ResultMutationTarget target,
                                                                 List<List<String>> rows,
                                                                 List<ResultOperation> operations) {
        return previewResultOperations(target, rows, emptyLocators(rows == null ? 0 : rows.size()), operations);
    }

    public static List<MutationPreview> previewResultOperations(ResultMutationTarget target,
                                                                 List<List<String>> rows,
                                                                 List<List<String>> rowLocators,
                                                                 List<ResultOperation> operations) {
        List<MutationPreview> result = new ArrayList<MutationPreview>();
        for (ResultOperation operation : operations) {
            if (operation.kind() == MutationKind.INSERT) {
                List<ValueChange> values = validatedValues(target, operation.values(), true);
                result.add(new MutationPreview(operation.operationId(), insertSql(target, values), bindPreview(values)));
                continue;
            }
            List<String> row = sourceRow(rows, operation.rowIndex());
            OperationLocator locator = operationLocator(target, row,
                    sourceLocator(rowLocators, operation.rowIndex()));
            if (locator == null) throw new QueryExecutionException("该行没有可用的安全定位器", null,
                    operation.operationId(), null);
            if (operation.kind() == MutationKind.DELETE) {
                result.add(new MutationPreview(operation.operationId(), deleteSql(target, locator),
                        locatorPreview(locator)));
            } else {
                List<ValueChange> values = validatedValues(target, operation.values(), false);
                List<String> binds = new ArrayList<String>(bindPreview(values));
                binds.addAll(locatorPreview(locator));
                result.add(new MutationPreview(operation.operationId(), updateOperationSql(target, locator, values), binds));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> bindPreview(List<ValueChange> values) {
        List<String> result = new ArrayList<String>();
        for (ValueChange value : values) if (!value.value().isDefault()) {
            String text = value.value().isNull() ? "NULL" : value.value().isLargeValueToken()
                    ? "<large value>" : value.value().value();
            result.add(text);
        }
        return result;
    }

    private static List<String> keyPreview(ResultMutationTarget target, ResultMutationTarget.Key key,
                                           List<String> row) {
        List<String> result = new ArrayList<String>();
        for (Integer index : key.resultColumnIndices()) {
            ResultMutationTarget.Column column = targetColumn(target, index);
            result.add(column.name() + "=" + row.get(index));
        }
        return result;
    }

    private static List<String> locatorPreview(OperationLocator locator) {
        List<String> result = new ArrayList<String>();
        for (int index = 0; index < locator.values.size(); index++) {
            result.add(locator.predicates.get(index).replace("?", "") + locator.values.get(index));
        }
        return result;
    }

    private static String resultChangeFailureDetail(Exception exception) {
        SQLException sqlException = exception instanceof SQLException
                ? (SQLException) exception : new SQLException(exception.getMessage(), exception);
        String detail = exception.getMessage() == null ? exception.getClass().getSimpleName()
                : sanitize(sqlException);
        String lower = detail.toLowerCase(java.util.Locale.ROOT);
        String state = sqlException.getSQLState();
        int code = Math.abs(sqlException.getErrorCode());
        if (code == 1205 || code == 54 || code == 30006 || lower.contains("lock wait timeout")
                || lower.contains("resource busy")) {
            return "等待行锁超时或 NOWAIT 冲突（" + detail + "）";
        }
        if (code == 1213 || code == 60 || lower.contains("deadlock")) {
            return "数据库检测到死锁，本批次已回滚（" + detail + "）";
        }
        if (code == 1062 || code == 1 || lower.contains("duplicate") || lower.contains("unique constraint")) {
            return "字段值违反唯一约束（" + detail + "）";
        }
        if (code == 1048 || code == 1400 || lower.contains("cannot be null")) {
            return "必填字段不能为 NULL（" + detail + "）";
        }
        if (code == 1406 || code == 12899 || lower.contains("data too long")) {
            return "字段值超过允许长度（" + detail + "）";
        }
        if (code == 1264 || code == 1438 || lower.contains("out of range")) {
            return "数值超过字段精度或范围（" + detail + "）";
        }
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
        } catch (SQLException unsupported) {
            if (!oceanBaseOracleSavepointReleaseUnsupported(unsupported)) throw unsupported;
            // OceanBase Connector/J 2.4.x reports this Oracle-mode limitation as a plain SQLException.
            LOG.debug("OceanBase Oracle JDBC 不支持主动释放保存点，将由事务结束时清理");
        }
    }

    private static boolean oceanBaseOracleSavepointReleaseUnsupported(SQLException exception) {
        String message = exception.getMessage() == null ? ""
                : exception.getMessage().toLowerCase(java.util.Locale.ROOT);
        return "99999".equals(exception.getSQLState()) && Math.abs(exception.getErrorCode()) == 17023
                && message.contains("releasesavepoint") && message.contains("not supported");
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

    private static OperationLocator operationLocator(ResultMutationTarget target, List<String> row,
                                                       List<String> rowLocator) {
        ResultMutationTarget.Locator configured = target.locator();
        if (configured != null && rowLocator != null
                && configured.predicates().size() == rowLocator.size()
                && configured.jdbcTypes().size() == rowLocator.size() && !containsNull(rowLocator)) {
            return new OperationLocator(configured.predicates(), configured.jdbcTypes(), rowLocator);
        }
        ResultMutationTarget.Key key = mutationKey(target, row);
        if (key == null) return null;
        List<String> predicates = new ArrayList<String>();
        List<Integer> types = new ArrayList<Integer>();
        List<String> values = new ArrayList<String>();
        for (Integer index : key.resultColumnIndices()) {
            ResultMutationTarget.Column column = targetColumn(target, index);
            predicates.add(column.quotedName() + " = ?");
            types.add(column.jdbcType());
            values.add(row.get(index));
        }
        return new OperationLocator(predicates, types, values);
    }

    private static List<String> updatedLocatorValues(ResultMutationTarget target, List<String> current,
                                                       List<String> updatedRow) {
        ResultMutationTarget.Locator locator = target.locator();
        if (locator == null) return Collections.emptyList();
        if ("ROWID".equals(locator.kind())) return current == null
                ? Collections.<String>emptyList() : new ArrayList<String>(current);
        if (locator.columnNames().size() != locator.predicates().size()) return current == null
                ? Collections.<String>emptyList() : new ArrayList<String>(current);
        List<String> values = new ArrayList<String>();
        for (String name : locator.columnNames()) {
            ResultMutationTarget.Column match = null;
            for (ResultMutationTarget.Column column : target.columns()) {
                if (column.name().equalsIgnoreCase(name)) { match = column; break; }
            }
            if (match == null || match.resultIndex() < 0 || match.resultIndex() >= updatedRow.size()) {
                return current == null ? Collections.<String>emptyList() : new ArrayList<String>(current);
            }
            values.add(updatedRow.get(match.resultIndex()));
        }
        return values;
    }

    private static boolean containsNull(List<String> values) {
        if (values == null || values.isEmpty()) return true;
        for (String value : values) if (value == null) return true;
        return false;
    }

    private static int bindLocator(PreparedStatement statement, int parameter,
                                   OperationLocator locator) throws SQLException {
        for (int index = 0; index < locator.values.size(); index++) {
            bind(statement, parameter++, locator.values.get(index), locator.jdbcTypes.get(index));
        }
        return parameter;
    }

    private static List<String> refreshRow(Connection connection, ResultMutationTarget target,
                                            OperationLocator locator, List<String> baseRow) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ");
        List<ResultMutationTarget.Column> columns = new ArrayList<ResultMutationTarget.Column>();
        for (ResultMutationTarget.Column column : target.columns()) {
            if (column.quotedName() != null && !column.quotedName().isEmpty()) columns.add(column);
        }
        Collections.sort(columns, (left, right) -> Integer.compare(left.resultIndex(), right.resultIndex()));
        if (columns.isEmpty()) throw new QueryExecutionException("目标结果没有可重新读取的直接字段", null);
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(columns.get(index).quotedName());
        }
        sql.append(" FROM ").append(target.qualifiedName());
        appendLocatorPredicate(sql, locator);
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bindLocator(statement, 1, locator);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new QueryExecutionException("更改已执行，但无法重新读取目标行", null);
                List<String> row = new ArrayList<String>(Collections.nCopies(visibleColumnCount(target), null));
                if (baseRow != null) for (int index = 0; index < Math.min(row.size(), baseRow.size()); index++) {
                    row.set(index, baseRow.get(index));
                }
                for (int index = 0; index < columns.size(); index++) {
                    row.set(columns.get(index).resultIndex(), displayValue(result.getObject(index + 1)));
                }
                if (result.next()) throw new QueryExecutionException("重新读取目标行时定位结果不唯一", null);
                return row;
            }
        }
    }

    private static List<String> refreshResultRow(Connection connection, ResultMutationTarget target,
                                                  OperationLocator locator, List<String> baseRow)
            throws SQLException {
        if (target.refreshSql().isEmpty()) return refreshRow(connection, target, locator, baseRow);
        try (PreparedStatement statement = connection.prepareStatement(target.refreshSql())) {
            bindLocator(statement, 1, locator);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                int count = visibleColumnCount(target);
                List<String> row = new ArrayList<String>(count);
                for (int index = 1; index <= count; index++) row.add(displayValue(result.getObject(index)));
                if (result.next()) throw new QueryExecutionException("重新执行原查询时行定位结果不唯一", null);
                return row;
            }
        }
    }

    private static final class OperationLocator {
        private final List<String> predicates;
        private final List<Integer> jdbcTypes;
        private final List<String> values;
        private OperationLocator(List<String> predicates, List<Integer> jdbcTypes, List<String> values) {
            this.predicates = new ArrayList<String>(predicates);
            this.jdbcTypes = new ArrayList<Integer>(jdbcTypes);
            this.values = new ArrayList<String>(values);
        }
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

    private static String updateOperationSql(ResultMutationTarget target, ResultMutationTarget.Key key,
                                             List<ValueChange> cells) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(target.qualifiedName()).append(" SET ");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) sql.append(", ");
            ValueChange cell = cells.get(index);
            sql.append(targetColumn(target, cell.columnIndex()).quotedName()).append(" = ")
                    .append(cell.value().isDefault() ? "DEFAULT" : "?");
        }
        appendKeyPredicate(sql, target, key);
        return sql.toString();
    }

    private static String updateOperationSql(ResultMutationTarget target, OperationLocator locator,
                                             List<ValueChange> cells) {
        StringBuilder sql = new StringBuilder("UPDATE ").append(target.qualifiedName()).append(" SET ");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) sql.append(", ");
            ValueChange cell = cells.get(index);
            sql.append(targetColumn(target, cell.columnIndex()).quotedName()).append(" = ")
                    .append(cell.value().isDefault() ? "DEFAULT" : "?");
        }
        appendLocatorPredicate(sql, locator);
        return sql.toString();
    }

    private static String deleteSql(ResultMutationTarget target, ResultMutationTarget.Key key) {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(target.qualifiedName());
        appendKeyPredicate(sql, target, key);
        return sql.toString();
    }

    private static String deleteSql(ResultMutationTarget target, OperationLocator locator) {
        StringBuilder sql = new StringBuilder("DELETE FROM ").append(target.qualifiedName());
        appendLocatorPredicate(sql, locator);
        return sql.toString();
    }

    private static String insertSql(ResultMutationTarget target, List<ValueChange> cells) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(target.qualifiedName()).append(" (");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(targetColumn(target, cells.get(index).columnIndex()).quotedName());
        }
        sql.append(") VALUES (");
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) sql.append(", ");
            sql.append(cells.get(index).value().isDefault() ? "DEFAULT" : "?");
        }
        return sql.append(')').toString();
    }

    private static void appendKeyPredicate(StringBuilder sql, ResultMutationTarget target,
                                           ResultMutationTarget.Key key) {
        sql.append(" WHERE ");
        for (int index = 0; index < key.resultColumnIndices().size(); index++) {
            if (index > 0) sql.append(" AND ");
            sql.append(targetColumn(target, key.resultColumnIndices().get(index)).quotedName()).append(" = ?");
        }
    }

    private static void appendLocatorPredicate(StringBuilder sql, OperationLocator locator) {
        sql.append(" WHERE ");
        for (int index = 0; index < locator.predicates.size(); index++) {
            if (index > 0) sql.append(" AND ");
            sql.append(locator.predicates.get(index));
        }
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

    private static Closeable bindValue(PreparedStatement statement, int index, MutationValue value,
                                       ResultMutationTarget.Column column) throws SQLException {
        if (value.isNull()) { statement.setNull(index, column.jdbcType()); return null; }
        if (value.isDefault()) throw new SQLException("DEFAULT 不应绑定为参数");
        if (value.isLargeValueFile()) {
            try {
                if ("clob".equals(column.typeFamily())) {
                    Reader reader = Files.newBufferedReader(Paths.get(value.value()), StandardCharsets.UTF_8);
                    statement.setCharacterStream(index, reader);
                    return reader;
                } else {
                    InputStream input = Files.newInputStream(Paths.get(value.value()));
                    statement.setBinaryStream(index, input);
                    return input;
                }
            } catch (IOException exception) {
                throw new SQLException("大字段草稿文件不可用", exception);
            }
        }
        String text = value.value();
        try {
            if ("raw".equals(column.typeFamily()) || "blob".equals(column.typeFamily())) {
                byte[] bytes = binaryValue(text);
                if ("blob".equals(column.typeFamily())) statement.setBinaryStream(index,
                        new java.io.ByteArrayInputStream(bytes), bytes.length);
                else statement.setBytes(index, bytes);
                return null;
            }
            if ("clob".equals(column.typeFamily())) {
                statement.setCharacterStream(index, new StringReader(text), text.length()); return null;
            }
            if (column.jdbcType() == Types.DATE && (text.contains(" ") || text.contains("T"))) {
                statement.setTimestamp(index, java.sql.Timestamp.valueOf(text.replace('T', ' '))); return null;
            }
            if (column.jdbcType() == Types.TIME_WITH_TIMEZONE) {
                statement.setObject(index, OffsetTime.parse(text)); return null;
            }
            if (column.jdbcType() == Types.TIME && (text.startsWith("-")
                    || text.matches("(?:[2-9]\\d|\\d{3,}):\\d{2}:\\d{2}(?:\\.\\d+)?"))) {
                // MySQL TIME is a duration and intentionally permits negative values and hours above 23.
                statement.setString(index, text); return null;
            }
            if (column.jdbcType() == Types.TIMESTAMP_WITH_TIMEZONE) {
                statement.setObject(index, OffsetDateTime.parse(text.replace(' ', 'T'))); return null;
            }
            if (column.jdbcType() == Types.NCHAR || column.jdbcType() == Types.NVARCHAR
                    || column.jdbcType() == Types.LONGNVARCHAR) {
                statement.setNString(index, text); return null;
            }
            bind(statement, index, text, column.jdbcType());
            return null;
        } catch (IllegalArgumentException exception) {
            throw new SQLException("值格式与字段类型不匹配：" + exception.getMessage(), exception);
        }
    }

    private static Closeable bindOperationValue(PreparedStatement statement, int parameter,
                                                ValueChange value, ResultMutationTarget target) {
        try {
            return bindValue(statement, parameter, value.value(), targetColumn(target, value.columnIndex()));
        } catch (SQLException exception) {
            throw new QueryExecutionException(resultChangeFailureDetail(exception), exception,
                    null, value.columnIndex());
        }
    }

    private static void addResource(List<Closeable> resources, Closeable resource) {
        if (resource != null) resources.add(resource);
    }

    private static void closeResources(List<Closeable> resources) {
        for (Closeable resource : resources) try { resource.close(); }
        catch (IOException ignored) { }
    }

    private static byte[] binaryValue(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
            if ((text.length() & 1) != 0 || !text.matches("[0-9a-fA-F]*")) {
                throw new IllegalArgumentException("二进制十六进制值格式无效");
            }
            byte[] bytes = new byte[text.length() / 2];
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) Integer.parseInt(text.substring(index * 2, index * 2 + 2), 16);
            }
            return bytes;
        }
        return Base64.getDecoder().decode(text);
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

    public enum MutationKind { UPDATE, INSERT, DELETE }

    public static final class MutationValue {
        private final String kind;
        private final String value;
        public MutationValue(String kind, String value) {
            String normalized = kind == null ? "text" : kind.trim().toLowerCase(java.util.Locale.ROOT);
            if (!"text".equals(normalized) && !"null".equals(normalized)
                    && !"default".equals(normalized) && !"largevaluetoken".equals(normalized)
                    && !"largevaluefile".equals(normalized)) {
                throw new IllegalArgumentException("未知的结果字段值类型");
            }
            this.kind = normalized;
            this.value = value;
        }
        public static MutationValue text(String value) { return new MutationValue("text", value); }
        public static MutationValue nullValue() { return new MutationValue("null", null); }
        public static MutationValue defaultValue() { return new MutationValue("default", null); }
        public static MutationValue largeValueFile(String path) { return new MutationValue("largevaluefile", path); }
        public String kind() { return kind; }
        public String value() { return value == null ? "" : value; }
        public boolean isNull() { return "null".equals(kind); }
        public boolean isDefault() { return "default".equals(kind); }
        public boolean isLargeValueToken() { return "largevaluetoken".equals(kind); }
        public boolean isLargeValueFile() { return "largevaluefile".equals(kind); }
        public String asDisplayValue() { return isNull() || isDefault() ? null : value; }
    }

    public static final class ValueChange {
        private final int columnIndex;
        private final MutationValue value;
        public ValueChange(int columnIndex, MutationValue value) {
            this.columnIndex = columnIndex;
            this.value = Objects.requireNonNull(value, "value");
        }
        public int columnIndex() { return columnIndex; }
        public MutationValue value() { return value; }
    }

    public static final class ResultOperation {
        private final String operationId;
        private final MutationKind kind;
        private final int rowIndex;
        private final List<ValueChange> values;
        public ResultOperation(String operationId, MutationKind kind, int rowIndex, List<ValueChange> values) {
            this.operationId = operationId == null || operationId.trim().isEmpty()
                    ? java.util.UUID.randomUUID().toString() : operationId;
            this.kind = Objects.requireNonNull(kind, "kind");
            this.rowIndex = rowIndex;
            this.values = values == null ? Collections.<ValueChange>emptyList()
                    : Collections.unmodifiableList(new ArrayList<ValueChange>(values));
        }
        public String operationId() { return operationId; }
        public MutationKind kind() { return kind; }
        public int rowIndex() { return rowIndex; }
        public List<ValueChange> values() { return values; }
    }

    public static final class OperationResult {
        private final String operationId;
        private final MutationKind kind;
        private final int rowIndex;
        private final List<String> row;
        private final List<String> locator;
        private final List<ValueChange> values;
        private final boolean visible;
        private OperationResult(String operationId, MutationKind kind, int rowIndex,
                                List<String> row, List<String> locator, List<ValueChange> values,
                                boolean visible) {
            this.operationId = operationId; this.kind = kind; this.rowIndex = rowIndex;
            this.row = Collections.unmodifiableList(new ArrayList<String>(row));
            this.locator = Collections.unmodifiableList(new ArrayList<String>(locator));
            this.values = Collections.unmodifiableList(new ArrayList<ValueChange>(values));
            this.visible = visible;
        }
        public String operationId() { return operationId; }
        public MutationKind kind() { return kind; }
        public int rowIndex() { return rowIndex; }
        public List<String> row() { return row; }
        public List<String> locator() { return locator; }
        public List<ValueChange> values() { return values; }
        public boolean visible() { return visible; }
    }

    public static final class MutationBatchResult {
        private final List<OperationResult> operations;
        private MutationBatchResult(List<OperationResult> operations) {
            this.operations = Collections.unmodifiableList(new ArrayList<OperationResult>(operations));
        }
        public List<OperationResult> operations() { return operations; }
    }

    public static final class MutationPreview {
        private final String operationId;
        private final String sql;
        private final List<String> binds;
        private MutationPreview(String operationId, String sql, List<String> binds) {
            this.operationId = operationId; this.sql = sql;
            this.binds = Collections.unmodifiableList(new ArrayList<String>(binds));
        }
        public String operationId() { return operationId; }
        public String sql() { return sql; }
        public List<String> binds() { return binds; }
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
    /**
     * 物理 JDBC 连接已被连接池从外部 abort 后，只退休本地执行器状态。
     * 这里不得再次调用 cancel、rollback 或 close，避免在已失效驱动会话上二次阻塞。
     */
    public void retireAborted() {
        cancelRequested.set(true);
        transactionDirty.set(false);
        executor.shutdownNow();
        LOG.warn("SQL执行器因物理JDBC连接被强制断开而退休");
    }
    public void setMaxRows(int maxRows) { this.maxRows = Math.max(1, maxRows); }
    public void setStreamBatchRows(int streamBatchRows) { this.streamBatchRows = Math.max(1, streamBatchRows); }

    private PageResult fetchPageBlocking(String sql, int offset, int limit) {
        Instant started = Instant.now();
        LOG.info("分页查询开始 offset={} limit={}", offset, limit);
        if (cancelRequested.get()) return PageResult.cancelledResult();
        PreparedResultQuery prepared = columnResolver.prepare(sql);
        try (Statement statement = session.jdbcConnection().createStatement()) {
            statement.setFetchSize(JDBC_FETCH_SIZE);
            activeStatement.set(statement);
            if (cancelRequested.get()) return PageResult.cancelledResult();
            if (!statement.execute(prepared.executionSql())) {
                throw new QueryExecutionException("该结果不是可分页的查询结果", null);
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                int skipped = 0;
                while (skipped < offset && !cancelRequested.get() && resultSet.next()) skipped++;
                if (cancelRequested.get()) return PageResult.cancelledResult();
                if (skipped < offset) return new PageResult(Collections.<List<String>>emptyList(), false);

                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                int visibleColumnCount = Math.max(0, columnCount - prepared.hiddenColumnCount());
                List<List<String>> rows = new ArrayList<List<String>>(limit);
                List<List<String>> locators = new ArrayList<List<String>>(limit);
                while (rows.size() < limit && !cancelRequested.get() && resultSet.next()) {
                    List<String> row = new ArrayList<String>(visibleColumnCount);
                    for (int index = 1; index <= visibleColumnCount; index++) {
                        row.add(displayValue(resultSet.getObject(index)));
                    }
                    rows.add(Collections.unmodifiableList(row));
                    List<String> locator = new ArrayList<String>(prepared.hiddenColumnCount());
                    for (int index = visibleColumnCount + 1; index <= columnCount; index++) {
                        locator.add(displayValue(resultSet.getObject(index)));
                    }
                    locators.add(Collections.unmodifiableList(locator));
                }
                if (cancelRequested.get()) return PageResult.cancelledResult();
                boolean hasMore = resultSet.next();
                if (cancelRequested.get()) return PageResult.cancelledResult();
                PageResult result = new PageResult(rows, newRowIds(rows.size()), locators, hasMore, false);
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
        LOG.info("SQL语句开始 index={} type={}", firstResultIndex, sqlStatement.type());
        if (cancelRequested.get()) return Collections.emptyList();
        PreparedResultQuery prepared = columnResolver.prepare(sqlStatement.text());
        try (Statement statement = session.jdbcConnection().createStatement()) {
            statement.setFetchSize(JDBC_FETCH_SIZE);
            statement.setMaxRows(statementMaxRows + 1);
            activeStatement.set(statement);
            if (cancelRequested.get()) return Collections.emptyList();
            boolean hasResult = statement.execute(prepared.executionSql());
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
                        output = readResultSet(sqlStatement, prepared, resultSet, started, resultIndex, listener,
                                statementMaxRows, statementBatchRows);
                    }
                } else {
                    int updateCount = statement.getUpdateCount();
                    if (updateCount == -1) break;
                    output = new StatementResult(sqlStatement.text(), sqlStatement.type(),
                            Collections.<String>emptyList(), Collections.<List<String>>emptyList(),
                            updateCount, false, Duration.between(started, Instant.now()), null);
                    listener.resultMetadata(resultIndex, sqlStatement, output.columnDetails());
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
                listener.resultMetadata(firstResultIndex, sqlStatement, output.columnDetails());
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
            listener.resultMetadata(firstResultIndex, sqlStatement, failure.columnDetails());
            listener.resultCompleted(firstResultIndex, failure);
            return Collections.singletonList(failure);
        } finally {
            activeStatement.set(null);
        }
    }

    private StatementResult readResultSet(SqlStatement sqlStatement, PreparedResultQuery prepared,
                                          ResultSet resultSet, Instant started,
                                          int resultIndex, QueryResultListener listener,
                                          int resultMaxRows, int resultBatchRows) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        int visibleColumnCount = Math.max(0, columnCount - prepared.hiddenColumnCount());
        List<String> columns = new ArrayList<String>(visibleColumnCount);
        List<ResultColumn> columnDetails = new ArrayList<ResultColumn>(visibleColumnCount);
        for (int index = 1; index <= visibleColumnCount; index++) {
            String label = metadata.getColumnLabel(index);
            String name = metadata.getColumnName(index);
            String display = label == null || label.trim().isEmpty() ? name : label;
            columns.add(display);
            columnDetails.add(new ResultColumn(display, name, metadata.getCatalogName(index),
                    metadata.getSchemaName(index), metadata.getTableName(index), metadata.getColumnTypeName(index), "",
                    metadata.getColumnType(index), display));
        }
        ResolvedResultMetadata resolved = columnResolver.resolve(prepared,
                Collections.unmodifiableList(columnDetails));
        columnDetails = resolved.columns();
        listener.resultMetadata(resultIndex, sqlStatement, columnDetails,
                resolved.mutationTarget());

        List<List<String>> rows = new ArrayList<List<String>>(Math.min(resultMaxRows, JDBC_FETCH_SIZE));
        List<String> rowIds = new ArrayList<String>(Math.min(resultMaxRows, JDBC_FETCH_SIZE));
        List<List<String>> rowLocators = new ArrayList<List<String>>(Math.min(resultMaxRows, JDBC_FETCH_SIZE));
        List<List<String>> batch = new ArrayList<List<String>>(Math.min(resultBatchRows, resultMaxRows));
        List<String> batchIds = new ArrayList<String>(Math.min(resultBatchRows, resultMaxRows));
        boolean truncated = false;
        while (!cancelRequested.get() && resultSet.next()) {
            if (rows.size() >= resultMaxRows) { truncated = true; break; }
            List<String> row = new ArrayList<String>(visibleColumnCount);
            for (int index = 1; index <= visibleColumnCount; index++) row.add(displayValue(resultSet.getObject(index)));
            List<String> locator = new ArrayList<String>(prepared.hiddenColumnCount());
            for (int index = visibleColumnCount + 1; index <= columnCount; index++) {
                locator.add(displayValue(resultSet.getObject(index)));
            }
            rows.add(row);
            rowLocators.add(Collections.unmodifiableList(locator));
            String rowId = java.util.UUID.randomUUID().toString();
            rowIds.add(rowId);
            batch.add(Collections.unmodifiableList(new ArrayList<String>(row)));
            batchIds.add(rowId);
            if (batch.size() == resultBatchRows) {
                listener.rows(resultIndex, Collections.unmodifiableList(new ArrayList<String>(batchIds)),
                        immutableRows(batch));
                batch.clear();
                batchIds.clear();
            }
        }
        if (!batch.isEmpty()) listener.rows(resultIndex,
                Collections.unmodifiableList(new ArrayList<String>(batchIds)), immutableRows(batch));
        return new StatementResult(sqlStatement.text(), sqlStatement.type(), columns, columnDetails,
                resolved.mutationTarget(), rows, rowIds, rowLocators, -1, truncated,
                Duration.between(started, Instant.now()), null);
    }

    private static List<List<String>> immutableRows(List<List<String>> rows) {
        List<List<String>> copied = new ArrayList<List<String>>(rows.size());
        for (List<String> row : rows) copied.add(Collections.unmodifiableList(new ArrayList<String>(row)));
        return Collections.unmodifiableList(copied);
    }

    public static final class PageResult {
        private final List<List<String>> rows;
        private final List<String> rowIds;
        private final List<List<String>> rowLocators;
        private final boolean hasMore;
        private final boolean cancelled;

        private PageResult(List<List<String>> rows, boolean hasMore) {
            this(rows, newRowIds(rows.size()), emptyLocators(rows.size()), hasMore, false);
        }

        private PageResult(List<List<String>> rows, List<String> rowIds, List<List<String>> rowLocators,
                           boolean hasMore, boolean cancelled) {
            this.rows = immutableRows(rows);
            this.rowIds = Collections.unmodifiableList(new ArrayList<String>(rowIds));
            this.rowLocators = immutableRows(rowLocators);
            this.hasMore = hasMore;
            this.cancelled = cancelled;
        }

        private static PageResult cancelledResult() {
            return new PageResult(Collections.<List<String>>emptyList(), Collections.<String>emptyList(),
                    Collections.<List<String>>emptyList(), true, true);
        }

        public List<List<String>> rows() { return rows; }
        public List<String> rowIds() { return rowIds; }
        public List<List<String>> rowLocators() { return rowLocators; }
        public boolean hasMore() { return hasMore; }
        public boolean cancelled() { return cancelled; }
    }

    private static List<String> newRowIds(int size) {
        List<String> result = new ArrayList<String>(size);
        for (int index = 0; index < size; index++) result.add(java.util.UUID.randomUUID().toString());
        return result;
    }

    private static List<List<String>> emptyLocators(int size) {
        List<List<String>> result = new ArrayList<List<String>>(size);
        for (int index = 0; index < size; index++) result.add(Collections.<String>emptyList());
        return result;
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

    private static <T> T withSqlCategory(SqlLogCategory category, Supplier<T> action) {
        try (SqlLogging.Scope ignored = SqlLogging.scope(category)) {
            return action.get();
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
        private final String operationId;
        private final Integer columnIndex;
        public QueryExecutionException(String message, Throwable cause) { this(message, cause, null, null); }
        public QueryExecutionException(String message, Throwable cause, String operationId, Integer columnIndex) {
            super(message, cause); this.operationId = operationId; this.columnIndex = columnIndex;
        }
        public String operationId() { return operationId; }
        public Integer columnIndex() { return columnIndex; }
        private QueryExecutionException withOperation(String value) {
            return operationId != null ? this : new QueryExecutionException(getMessage(), getCause(), value, columnIndex);
        }
    }
}
