package com.dbstudio.desktop.query;

import com.dbstudio.spi.StatementType;
import java.util.List;

/** Receives ordered query output on the editor session's dedicated query thread. */
public interface QueryResultListener {
    void resultStarted(int resultIndex, String sql, StatementType type, List<String> columns);
    void rows(int resultIndex, List<List<String>> rows);
    void resultCompleted(int resultIndex, StatementResult result);

    QueryResultListener NONE = new QueryResultListener() {
        @Override public void resultStarted(int resultIndex, String sql, StatementType type, List<String> columns) { }
        @Override public void rows(int resultIndex, List<List<String>> rows) { }
        @Override public void resultCompleted(int resultIndex, StatementResult result) { }
    };
}
