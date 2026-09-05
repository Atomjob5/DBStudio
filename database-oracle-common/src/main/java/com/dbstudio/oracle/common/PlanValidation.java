package com.dbstudio.oracle.common;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.*;
import java.sql.SQLException;
import java.util.List;

public final class PlanValidation {
    private PlanValidation() { }
    public static void validate(String sql) throws SQLException {
        try {
            List<SQLStatement> parsed = SQLUtils.parseStatements(sql, "oracle");
            if (parsed.size() != 1) throw new IllegalArgumentException();
            SQLStatement s = parsed.get(0);
            if (!(s instanceof SQLSelectStatement || s instanceof SQLInsertStatement
                    || s instanceof SQLUpdateStatement || s instanceof SQLDeleteStatement
                    || s instanceof SQLMergeStatement)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new SQLException("执行计划仅支持单条 SELECT、INSERT、UPDATE、DELETE 或 MERGE", e);
        }
    }
}
