package com.dbstudio.desktop.query;

import java.util.List;

/** 解析结果列来源以及结果集安全编辑所需的可选元数据。 */
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
