package com.dbstudio.desktop.query;

import com.dbstudio.spi.StatementType;
import java.util.List;

/** 在编辑器专属查询线程上接收有序查询输出。 */
public interface QueryResultListener {
    void resultStarted(int resultIndex, String sql, StatementType type, List<String> columns);
    default void resultMetadata(int resultIndex, String sql, StatementType type, List<ResultColumn> columns) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<String>(columns.size());
        for (ResultColumn column : columns) labels.add(column.label());
        resultStarted(resultIndex, sql, type, labels);
    }
    default void resultMetadata(int resultIndex, String sql, StatementType type, List<ResultColumn> columns,
                                ResultMutationTarget mutationTarget) {
        resultMetadata(resultIndex, sql, type, columns);
    }
    void rows(int resultIndex, List<List<String>> rows);
    void resultCompleted(int resultIndex, StatementResult result);

    QueryResultListener NONE = new QueryResultListener() {
        @Override public void resultStarted(int resultIndex, String sql, StatementType type, List<String> columns) { }
        @Override public void rows(int resultIndex, List<List<String>> rows) { }
        @Override public void resultCompleted(int resultIndex, StatementResult result) { }
    };
}
