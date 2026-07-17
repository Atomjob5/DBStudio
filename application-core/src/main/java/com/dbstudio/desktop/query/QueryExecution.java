package com.dbstudio.desktop.query;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QueryExecution {
    private final List<StatementResult> results;
    private final Duration duration;
    private final boolean cancelled;

    public QueryExecution(List<StatementResult> results, Duration duration, boolean cancelled) {
        this.results = Collections.unmodifiableList(new ArrayList<StatementResult>(results));
        this.duration = duration == null ? Duration.ZERO : duration;
        this.cancelled = cancelled;
    }

    public List<StatementResult> results() { return results; }
    public Duration duration() { return duration; }
    public boolean cancelled() { return cancelled; }

    public boolean failed() {
        for (StatementResult result : results) if (result.failed()) return true;
        return false;
    }

    public long affectedRows() {
        long count = 0;
        for (StatementResult result : results) {
            count += result.hasRows() ? result.rows().size() : Math.max(0, result.updateCount());
        }
        return count;
    }
}
