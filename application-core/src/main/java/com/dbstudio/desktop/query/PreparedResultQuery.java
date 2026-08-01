package com.dbstudio.desktop.query;

import com.dbstudio.spi.ResultMutationSource;

/** Query text prepared with server-only locator columns for editable result rows. */
public final class PreparedResultQuery {
    private final String originalSql;
    private final String executionSql;
    private final int hiddenColumnCount;
    private final ResultMutationTarget.Locator locator;
    private final ResultMutationSource mutationSource;

    public PreparedResultQuery(String sql) { this(sql, sql, 0, null, null); }

    public PreparedResultQuery(String originalSql, String executionSql, int hiddenColumnCount,
                               ResultMutationTarget.Locator locator) {
        this(originalSql, executionSql, hiddenColumnCount, locator, null);
    }

    public PreparedResultQuery(String originalSql, String executionSql, int hiddenColumnCount,
                               ResultMutationTarget.Locator locator, ResultMutationSource mutationSource) {
        this.originalSql = originalSql;
        this.executionSql = executionSql;
        this.hiddenColumnCount = Math.max(0, hiddenColumnCount);
        this.locator = locator;
        this.mutationSource = mutationSource;
    }

    public String originalSql() { return originalSql; }
    public String executionSql() { return executionSql; }
    public int hiddenColumnCount() { return hiddenColumnCount; }
    public ResultMutationTarget.Locator locator() { return locator; }
    public ResultMutationSource mutationSource() { return mutationSource; }
}
