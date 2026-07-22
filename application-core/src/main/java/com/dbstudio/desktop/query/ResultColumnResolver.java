package com.dbstudio.desktop.query;

import java.util.List;

/** Resolves optional database metadata without using the active streaming query connection. */
public interface ResultColumnResolver {
    ResolvedResultMetadata resolve(String sql, List<ResultColumn> columns);
    void invalidate();

    ResultColumnResolver NONE = new ResultColumnResolver() {
        @Override public ResolvedResultMetadata resolve(String sql, List<ResultColumn> columns) {
            return new ResolvedResultMetadata(columns, null);
        }
        @Override public void invalidate() { }
    };
}
