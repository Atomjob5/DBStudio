package com.dbstudio.desktop.query;

import com.dbstudio.spi.StatementType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StatementResult {
    private final String sql;
    private final StatementType type;
    private final List<String> columns;
    private final List<ResultColumn> columnDetails;
    private final ResultMutationTarget mutationTarget;
    private final List<List<String>> rows;
    private final List<String> rowIds;
    private final List<List<String>> rowLocators;
    private final long updateCount;
    private final boolean truncated;
    private final Duration duration;
    private final String errorMessage;
    private boolean executionPlanResult;
    private com.dbstudio.spi.ExecutionPlan executionPlan;

    public static StatementResult plan(String sql, StatementType type, com.dbstudio.spi.ExecutionPlan plan,
                                       Duration duration, String error) {
        StatementResult result = new StatementResult(sql, type, Collections.<String>emptyList(),
                Collections.<List<String>>emptyList(), -1, false, duration, error);
        result.executionPlanResult = true;
        result.executionPlan = plan;
        return result;
    }
    public boolean isExecutionPlan() { return executionPlanResult; }
    public com.dbstudio.spi.ExecutionPlan executionPlan() { return executionPlan; }

    public StatementResult(String sql, StatementType type, List<String> columns, List<List<String>> rows,
                           long updateCount, boolean truncated, Duration duration, String errorMessage) {
        this(sql, type, columns, defaultDetails(columns), rows, updateCount, truncated, duration, errorMessage);
    }

    public StatementResult(String sql, StatementType type, List<String> columns, List<ResultColumn> columnDetails,
                           List<List<String>> rows, long updateCount, boolean truncated, Duration duration,
                           String errorMessage) {
        this(sql, type, columns, columnDetails, null, rows, updateCount, truncated, duration, errorMessage);
    }

    public StatementResult(String sql, StatementType type, List<String> columns, List<ResultColumn> columnDetails,
                           ResultMutationTarget mutationTarget, List<List<String>> rows, long updateCount,
                           boolean truncated, Duration duration, String errorMessage) {
        this(sql, type, columns, columnDetails, mutationTarget, rows, null, updateCount,
                truncated, duration, errorMessage);
    }

    public StatementResult(String sql, StatementType type, List<String> columns, List<ResultColumn> columnDetails,
                           ResultMutationTarget mutationTarget, List<List<String>> rows, List<String> rowIds,
                           long updateCount, boolean truncated, Duration duration, String errorMessage) {
        this(sql, type, columns, columnDetails, mutationTarget, rows, rowIds, null,
                updateCount, truncated, duration, errorMessage);
    }

    public StatementResult(String sql, StatementType type, List<String> columns, List<ResultColumn> columnDetails,
                           ResultMutationTarget mutationTarget, List<List<String>> rows, List<String> rowIds,
                           List<List<String>> rowLocators, long updateCount, boolean truncated,
                           Duration duration, String errorMessage) {
        this.sql = sql;
        this.type = type;
        this.columns = columns == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(columns));
        this.columnDetails = columnDetails == null ? Collections.<ResultColumn>emptyList()
                : Collections.unmodifiableList(new ArrayList<ResultColumn>(columnDetails));
        this.mutationTarget = mutationTarget;
        this.rows = immutableRows(rows);
        List<String> identifiers;
        if (rowIds instanceof ChunkedList && rowIds.size() == this.rows.size()) {
            @SuppressWarnings("unchecked") List<String> shared = (List<String>) rowIds;
            identifiers = shared;
        } else if (rowIds != null && rowIds.size() == this.rows.size()) {
            identifiers = new ArrayList<String>(rowIds);
        }
        else {
            identifiers = new ArrayList<String>(this.rows.size());
            for (int index = 0; index < this.rows.size(); index++) {
                identifiers.add(java.util.UUID.randomUUID().toString());
            }
        }
        this.rowIds = identifiers instanceof ChunkedList ? identifiers
                : Collections.unmodifiableList(new ArrayList<String>(identifiers));
        this.rowLocators = immutableLocators(rowLocators, this.rows.size());
        this.updateCount = updateCount;
        this.truncated = truncated;
        this.duration = duration == null ? Duration.ZERO : duration;
        this.errorMessage = errorMessage;
    }

    public String sql() { return sql; }
    public StatementType type() { return type; }
    public List<String> columns() { return columns; }
    public List<ResultColumn> columnDetails() { return columnDetails; }
    public ResultMutationTarget mutationTarget() { return mutationTarget; }
    public List<List<String>> rows() { return rows; }
    /** Opaque row identities scoped to this execution result. */
    public List<String> rowIds() { return rowIds; }
    /** Server-only physical locator values parallel to rows. */
    public List<List<String>> rowLocators() { return rowLocators; }
    public long updateCount() { return updateCount; }
    public boolean truncated() { return truncated; }
    public Duration duration() { return duration; }
    public String errorMessage() { return errorMessage; }
    public boolean hasRows() { return !columns.isEmpty(); }
    public boolean failed() { return errorMessage != null && !errorMessage.trim().isEmpty(); }

    private static List<List<String>> immutableRows(List<List<String>> source) {
        if (source == null) return ChunkedList.empty();
        if (source instanceof ChunkedList) return source;
        ChunkedList.Builder<List<String>> builder = new ChunkedList.Builder<List<String>>();
        for (List<String> row : source) {
            builder.add(Collections.unmodifiableList(new ArrayList<String>(row)));
        }
        return builder.build();
    }

    private static List<List<String>> immutableLocators(List<List<String>> source, int size) {
        if (source instanceof ChunkedList && source.size() == size) return source;
        ChunkedList.Builder<List<String>> builder = new ChunkedList.Builder<List<String>>();
        for (int index = 0; index < size; index++) {
            List<String> values = source != null && index < source.size()
                    ? source.get(index) : Collections.<String>emptyList();
            builder.add(Collections.unmodifiableList(new ArrayList<String>(values)));
        }
        return builder.build();
    }

    private static List<ResultColumn> defaultDetails(List<String> columns) {
        if (columns == null) return Collections.emptyList();
        List<ResultColumn> result = new ArrayList<ResultColumn>(columns.size());
        for (String column : columns) result.add(new ResultColumn(column, column, "", "", "", "", ""));
        return result;
    }
}
