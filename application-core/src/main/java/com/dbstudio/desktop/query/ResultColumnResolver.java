package com.dbstudio.desktop.query;

import java.util.List;

/** 使用独立元数据会话解析可选列信息，不在活动流式查询连接上执行额外语句。 */
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
