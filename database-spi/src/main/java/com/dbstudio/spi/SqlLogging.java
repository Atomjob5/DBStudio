package com.dbstudio.spi;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC logging proxy. It records SQL inputs and counters, but never reads or formats ResultSet values.
 */
public final class SqlLogging {
    private static final ThreadLocal<Deque<SqlLogCategory>> SCOPES =
            new ThreadLocal<Deque<SqlLogCategory>>() {
                @Override protected Deque<SqlLogCategory> initialValue() {
                    return new ArrayDeque<SqlLogCategory>();
                }
            };

    private SqlLogging() { }

    /** Wraps a physical connection once. Re-wrapping only changes its default category. */
    public static Connection wrap(Connection connection, SqlLogCategory category) {
        if (connection == null) throw new NullPointerException("connection");
        ConnectionHandler existing = connectionHandler(connection);
        if (existing != null) {
            existing.defaultCategory.set(category);
            return connection;
        }
        ConnectionHandler handler = new ConnectionHandler(connection, category);
        return (Connection) Proxy.newProxyInstance(connection.getClass().getClassLoader(),
                new Class<?>[]{Connection.class}, handler);
    }

    /** Changes the default category of a wrapped connection, wrapping it if necessary. */
    public static Connection categorize(Connection connection, SqlLogCategory category) {
        return wrap(connection, category);
    }

    /** Temporarily overrides the category for statements created and executed on the current thread. */
    public static Scope scope(SqlLogCategory category) {
        if (category == null) throw new NullPointerException("category");
        final Deque<SqlLogCategory> stack = SCOPES.get();
        stack.push(category);
        return new Scope(stack, category);
    }

    public static final class Scope implements AutoCloseable {
        private final Deque<SqlLogCategory> stack;
        private final SqlLogCategory category;
        private boolean closed;

        private Scope(Deque<SqlLogCategory> stack, SqlLogCategory category) {
            this.stack = stack; this.category = category;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (!stack.isEmpty() && stack.peek() == category) stack.pop();
            else stack.removeFirstOccurrence(category);
            if (stack.isEmpty()) SCOPES.remove();
        }
    }

    private static SqlLogCategory category(SqlLogCategory fallback) {
        Deque<SqlLogCategory> stack = SCOPES.get();
        return stack.isEmpty() ? fallback : stack.peek();
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;
        private final AtomicReference<SqlLogCategory> defaultCategory;

        private ConnectionHandler(Connection delegate, SqlLogCategory category) {
            this.delegate = delegate;
            this.defaultCategory = new AtomicReference<SqlLogCategory>(category);
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("unwrap".equals(name) && args != null && args.length == 1) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) return proxy;
            }
            if ("isWrapperFor".equals(name) && args != null && args.length == 1
                    && ((Class<?>) args[0]).isInstance(proxy)) return true;
            try {
                Object result = method.invoke(delegate, args);
                if (result instanceof Statement && isStatementFactory(name)) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String
                            ? (String) args[0] : null;
                    return wrapStatement((Statement) result, sql, defaultCategory.get());
                }
                return result;
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private static boolean isStatementFactory(String name) {
        return "createStatement".equals(name) || "prepareStatement".equals(name)
                || "prepareCall".equals(name);
    }

    private static Statement wrapStatement(Statement statement, String preparedSql,
                                           SqlLogCategory defaultCategory) {
        Class<?> contract = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(statement.getClass().getClassLoader(),
                new Class<?>[]{contract}, new StatementHandler(statement, preparedSql, defaultCategory));
    }

    private static final class StatementHandler implements InvocationHandler {
        private final Statement delegate;
        private final String preparedSql;
        private final SqlLogCategory defaultCategory;
        private final Map<Integer, ParameterValue> parameters = new LinkedHashMap<Integer, ParameterValue>();
        private final List<Map<Integer, ParameterValue>> batches = new ArrayList<Map<Integer, ParameterValue>>();
        private final List<String> batchSql = new ArrayList<String>();
        private Execution pending;

        private StatementHandler(Statement delegate, String preparedSql, SqlLogCategory defaultCategory) {
            this.delegate = delegate; this.preparedSql = preparedSql; this.defaultCategory = defaultCategory;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("unwrap".equals(name) && args != null && args.length == 1) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) return proxy;
            }
            if ("isWrapperFor".equals(name) && args != null && args.length == 1
                    && ((Class<?>) args[0]).isInstance(proxy)) return true;
            if (isParameterSetter(name, args)) {
                parameters.put((Integer) args[0], parameterValue(name, args));
            } else if ("clearParameters".equals(name)) {
                parameters.clear();
            } else if ("addBatch".equals(name) && preparedSql != null && (args == null || args.length == 0)) {
                batches.add(new LinkedHashMap<Integer, ParameterValue>(parameters));
            } else if ("addBatch".equals(name) && preparedSql == null && args != null
                    && args.length == 1 && args[0] instanceof String) {
                batchSql.add((String) args[0]);
            } else if ("clearBatch".equals(name)) {
                batches.clear();
                batchSql.clear();
            }

            if (isExecute(name)) return execute(method, args, name);
            try {
                Object result = method.invoke(delegate, args);
                if (result instanceof ResultSet) return wrapResultSet((ResultSet) result, pending,
                        "executeQuery".equals(name));
                if ("close".equals(name)) finishPending();
                return result;
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        private Object execute(Method method, Object[] args, String methodName) throws Throwable {
            finishPending();
            String sql = preparedSql != null ? preparedSql
                    : args != null && args.length > 0 && args[0] instanceof String ? (String) args[0] : "";
            if (("executeBatch".equals(methodName) || "executeLargeBatch".equals(methodName))
                    && preparedSql == null && !batchSql.isEmpty()) sql = formatSqlBatch(batchSql);
            boolean batchExecution = "executeBatch".equals(methodName) || "executeLargeBatch".equals(methodName);
            List<Map<Integer, ParameterValue>> batchValues = batchExecution
                    ? new ArrayList<Map<Integer, ParameterValue>>(batches) : Collections.<Map<Integer, ParameterValue>>emptyList();
            pending = new Execution(category(defaultCategory), sql,
                    batchExecution ? Collections.<Integer, ParameterValue>emptyMap()
                            : new LinkedHashMap<Integer, ParameterValue>(parameters), batchValues);
            pending.start();
            try {
                Object result = method.invoke(delegate, args);
                if (result instanceof ResultSet) {
                    return wrapResultSet((ResultSet) result, pending, true);
                }
                if (result instanceof int[]) {
                    pending.batch(((int[]) result).length, affected((int[]) result));
                    pending.success(); batches.clear(); batchSql.clear();
                } else if (result instanceof long[]) {
                    pending.batch(((long[]) result).length, affected((long[]) result));
                    pending.success(); batches.clear(); batchSql.clear();
                } else if ("executeUpdate".equals(methodName) || "executeLargeUpdate".equals(methodName)) {
                    pending.affected(result instanceof Number ? ((Number) result).longValue() : -1L);
                    pending.success();
                } else if (result instanceof Boolean && !((Boolean) result).booleanValue()) {
                    int count = delegate.getUpdateCount();
                    if (count >= 0) pending.affected(count);
                }
                return result;
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                pending.failure(cause);
                throw cause;
            }
        }

        private void finishPending() {
            if (pending != null) pending.success();
            pending = null;
        }
    }

    private static ResultSet wrapResultSet(ResultSet resultSet, Execution execution, boolean finishOnClose) {
        if (resultSet == null || execution == null) return resultSet;
        return (ResultSet) Proxy.newProxyInstance(resultSet.getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class}, new ResultSetHandler(resultSet, execution, finishOnClose));
    }

    private static final class ResultSetHandler implements InvocationHandler {
        private final ResultSet delegate;
        private final Execution execution;
        private final boolean finishOnClose;

        private ResultSetHandler(ResultSet delegate, Execution execution, boolean finishOnClose) {
            this.delegate = delegate; this.execution = execution; this.finishOnClose = finishOnClose;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if ("unwrap".equals(name) && args != null && args.length == 1) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) return proxy;
            }
            if ("isWrapperFor".equals(name) && args != null && args.length == 1
                    && ((Class<?>) args[0]).isInstance(proxy)) return true;
            try {
                Object result = method.invoke(delegate, args);
                if ("next".equals(name) && Boolean.TRUE.equals(result)) execution.row();
                if ("close".equals(name) && finishOnClose) execution.success();
                return result;
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                execution.failure(cause);
                throw cause;
            }
        }
    }

    private static final class Execution {
        private final SqlLogCategory category;
        private final String id = UUID.randomUUID().toString();
        private final String sql;
        private final Map<Integer, ParameterValue> parameters;
        private final List<Map<Integer, ParameterValue>> batches;
        private final long started = System.nanoTime();
        private final AtomicLong rows = new AtomicLong();
        private final AtomicBoolean finished = new AtomicBoolean();
        private long affectedRows = -1L;
        private int batchCount;

        private Execution(SqlLogCategory category, String sql, Map<Integer, ParameterValue> parameters,
                          List<Map<Integer, ParameterValue>> batches) {
            this.category = category; this.sql = sql == null ? "" : sql;
            this.parameters = parameters; this.batches = batches;
        }

        private Logger logger() {
            return LoggerFactory.getLogger("com.dbstudio.sql." + category.loggerSuffix());
        }

        private void start() {
            logger().info("event=sql-start sqlExecutionId={} sql=\"{}\" params={} batchParams={}",
                    id, escape(sql), formatParameters(parameters), formatBatches(batches));
        }

        private void row() { rows.incrementAndGet(); }
        private void affected(long count) { affectedRows = count; }
        private void batch(int count, long affected) { batchCount = count; affectedRows = affected; }

        private void success() {
            if (!finished.compareAndSet(false, true)) return;
            logger().info("event=sql-complete sqlExecutionId={} status=SUCCESS returnedRows={} affectedRows={} batchCount={} durationMs={}",
                    id, rows.get(), affectedRows, batchCount, elapsedMillis());
        }

        private void failure(Throwable throwable) {
            if (!finished.compareAndSet(false, true)) return;
            SQLException sqlException = findSqlException(throwable);
            logger().info("event=sql-complete sqlExecutionId={} status={} returnedRows={} affectedRows={} batchCount={} durationMs={} sqlState={} errorCode={}",
                    id, cancelled(sqlException) ? "CANCELLED" : "FAILED", rows.get(), affectedRows, batchCount,
                    elapsedMillis(), sqlException == null ? "" : safe(sqlException.getSQLState()),
                    sqlException == null ? 0 : sqlException.getErrorCode());
        }

        private long elapsedMillis() { return (System.nanoTime() - started) / 1_000_000L; }
    }

    private static boolean isExecute(String name) {
        return "execute".equals(name) || "executeQuery".equals(name) || "executeUpdate".equals(name)
                || "executeLargeUpdate".equals(name) || "executeBatch".equals(name)
                || "executeLargeBatch".equals(name);
    }

    private static boolean isParameterSetter(String name, Object[] args) {
        return name.startsWith("set") && args != null && args.length >= 2 && args[0] instanceof Integer;
    }

    private static ParameterValue parameterValue(String method, Object[] args) {
        if ("setNull".equals(method)) return new ParameterValue("NULL(jdbcType=" + args[1] + ")");
        Object value = args[1];
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            return new ParameterValue("BINARY(length=" + bytes.length + ",sha256=" + sha256(bytes) + ")");
        }
        if (value instanceof Blob) return new ParameterValue("BLOB(length=" + length((Blob) value) + ")");
        if (value instanceof Clob) return new ParameterValue("CLOB(length=" + length((Clob) value) + ")");
        if (value instanceof InputStream || value instanceof Reader) {
            Object declaredLength = args.length > 2 && args[2] instanceof Number ? args[2] : "unknown";
            return new ParameterValue((value instanceof InputStream ? "BINARY_STREAM" : "CHARACTER_STREAM")
                    + "(length=" + declaredLength + ")");
        }
        if (value == null) return new ParameterValue("null");
        return new ParameterValue(value.getClass().getSimpleName() + "(\"" + escape(String.valueOf(value)) + "\")");
    }

    private static String formatParameters(Map<Integer, ParameterValue> values) {
        if (values.isEmpty()) return "[]";
        StringBuilder result = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<Integer, ParameterValue> entry : values.entrySet()) {
            if (!first) result.append(','); first = false;
            result.append(entry.getKey()).append('=').append(entry.getValue().text);
        }
        return result.append(']').toString();
    }

    private static String formatBatches(List<Map<Integer, ParameterValue>> batches) {
        if (batches.isEmpty()) return "[]";
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < batches.size(); index++) {
            if (index > 0) result.append(',');
            result.append(formatParameters(batches.get(index)));
        }
        return result.append(']').toString();
    }

    private static String formatSqlBatch(List<String> statements) {
        StringBuilder result = new StringBuilder("BATCH[");
        for (int index = 0; index < statements.size(); index++) {
            if (index > 0) result.append(';');
            result.append(statements.get(index));
        }
        return result.append(']').toString();
    }

    private static final class ParameterValue {
        private final String text;
        private ParameterValue(String text) { this.text = text; }
    }

    private static long affected(int[] counts) {
        long result = 0;
        for (int count : counts) if (count >= 0) result += count;
        return result;
    }

    private static long affected(long[] counts) {
        long result = 0;
        for (long count : counts) if (count >= 0) result += count;
        return result;
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\\') result.append("\\\\");
            else if (character == '"') result.append("\\\"");
            else if (character == '\n') result.append("\\n");
            else if (character == '\r') result.append("\\r");
            else if (character == '\t') result.append("\\t");
            else if (Character.isISOControl(character)) result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            else result.append(character);
        }
        return result.toString();
    }

    private static String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
            return result.toString();
        } catch (Exception exception) {
            return "unavailable";
        }
    }

    private static long length(Blob value) {
        try { return value.length(); } catch (SQLException ignored) { return -1L; }
    }

    private static long length(Clob value) {
        try { return value.length(); } catch (SQLException ignored) { return -1L; }
    }

    private static SQLException findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException) return (SQLException) current;
            current = current.getCause();
        }
        return null;
    }

    private static boolean cancelled(SQLException exception) {
        if (exception == null) return false;
        String state = safe(exception.getSQLState());
        String message = safe(exception.getMessage()).toLowerCase(Locale.ROOT);
        return "57014".equals(state) || message.contains("cancel") || message.contains("interrupt");
    }

    private static String safe(String value) { return value == null ? "" : escape(value); }

    private static ConnectionHandler connectionHandler(Connection connection) {
        if (!Proxy.isProxyClass(connection.getClass())) return null;
        try {
            InvocationHandler handler = Proxy.getInvocationHandler(connection);
            return handler instanceof ConnectionHandler ? (ConnectionHandler) handler : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
