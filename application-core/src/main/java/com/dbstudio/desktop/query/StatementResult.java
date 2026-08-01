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
        if (rows == null) {
            this.rows = Collections.emptyList();
        } else {
            List<List<String>> copied = new ArrayList<List<String>>(rows.size());
            for (List<String> row : rows) {
                copied.add(Collections.unmodifiableList(new ArrayList<String>(row)));
            }
            this.rows = Collections.unmodifiableList(copied);
        }
        List<String> identifiers = new ArrayList<String>(this.rows.size());
        if (rowIds != null && rowIds.size() == this.rows.size()) identifiers.addAll(rowIds);
        else for (int index = 0; index < this.rows.size(); index++) {
            identifiers.add(java.util.UUID.randomUUID().toString());
        }
        this.rowIds = Collections.unmodifiableList(identifiers);
        List<List<String>> locators = new ArrayList<List<String>>(this.rows.size());
        for (int index = 0; index < this.rows.size(); index++) {
            List<String> values = rowLocators != null && index < rowLocators.size()
                    ? rowLocators.get(index) : Collections.<String>emptyList();
            locators.add(Collections.unmodifiableList(new ArrayList<String>(values)));
        }
        this.rowLocators = Collections.unmodifiableList(locators);
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

    private static List<ResultColumn> defaultDetails(List<String> columns) {
        if (columns == null) return Collections.emptyList();
        List<ResultColumn> result = new ArrayList<ResultColumn>(columns.size());
        for (String column : columns) result.add(new ResultColumn(column, column, "", "", "", "", ""));
        return result;
    }
}
