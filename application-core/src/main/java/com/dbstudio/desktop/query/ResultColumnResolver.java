package com.dbstudio.desktop.query;

import java.util.List;

/** Resolves optional database metadata without using the active streaming query connection. */
public interface ResultColumnResolver {
    List<ResultColumn> resolve(String sql, List<ResultColumn> columns);
    void invalidate();

    ResultColumnResolver NONE = new ResultColumnResolver() {
        @Override public List<ResultColumn> resolve(String sql, List<ResultColumn> columns) { return columns; }
        @Override public void invalidate() { }
    };
}
