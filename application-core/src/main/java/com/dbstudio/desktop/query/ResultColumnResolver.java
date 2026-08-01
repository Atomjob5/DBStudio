package com.dbstudio.desktop.query;

import java.util.List;

/** 解析结果列来源以及结果集安全编辑所需的可选元数据。 */
public interface ResultColumnResolver {
    default PreparedResultQuery prepare(String sql) { return new PreparedResultQuery(sql); }

    ResolvedResultMetadata resolve(String sql, List<ResultColumn> columns);

    default ResolvedResultMetadata resolve(PreparedResultQuery query, List<ResultColumn> columns) {
        ResolvedResultMetadata resolved = resolve(query.originalSql(), columns);
        if (query.locator() == null || resolved.mutationTarget() == null) return resolved;
        return new ResolvedResultMetadata(resolved.columns(), resolved.mutationTarget().withLocator(query.locator()));
    }
    void invalidate();

    ResultColumnResolver NONE = new ResultColumnResolver() {
        @Override public ResolvedResultMetadata resolve(String sql, List<ResultColumn> columns) {
            return new ResolvedResultMetadata(columns, null);
        }
        @Override public void invalidate() { }
    };
}
