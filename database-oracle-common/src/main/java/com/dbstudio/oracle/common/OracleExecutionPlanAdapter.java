package com.dbstudio.oracle.common;

import com.dbstudio.spi.*;
import java.sql.*;
import java.util.*;

public final class OracleExecutionPlanAdapter implements ExecutionPlanAdapter {
    @Override public void validate(String sql) throws SQLException { PlanValidation.validate(sql); }

    @Override public ExecutionPlan explain(DatabaseSession session, String sql, Control control) throws SQLException {
        validate(sql);
        Connection connection = session.jdbcConnection();
        boolean autoCommit = connection.getAutoCommit();
        boolean localTransactionStarted = false;
        Savepoint savepoint = null;
        SQLException failure = null;
        try {
            control.checkCancelled();
            if (autoCommit) {
                connection.setAutoCommit(false);
                localTransactionStarted = true;
            }
            else savepoint = connection.setSavepoint();
            String id = "DBS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
            try (Statement statement = connection.createStatement()) {
                control.active(statement);
                statement.execute("EXPLAIN PLAN SET STATEMENT_ID = '" + id + "' FOR " + sql);
            }
            List<ExecutionPlan.Node> nodes = new ArrayList<ExecutionPlan.Node>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ID, PARENT_ID, OPERATION, OPTIONS, OBJECT_OWNER, OBJECT_NAME, CARDINALITY, COST, "
                    + "ACCESS_PREDICATES, FILTER_PREDICATES FROM PLAN_TABLE WHERE STATEMENT_ID = ? ORDER BY ID")) {
                statement.setString(1, id); control.active(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        control.checkCancelled();
                        Map<String, String> details = new LinkedHashMap<String, String>();
                        details.put("访问条件", rows.getString(9)); details.put("过滤条件", rows.getString(10));
                        String operation = rows.getString(3), name = rows.getString(6), owner = rows.getString(5);
                        nodes.add(new ExecutionPlan.Node(rows.getString(1), rows.getString(2), operation,
                                name == null ? null : owner == null ? name : owner + "." + name, rows.getString(4),
                                operation != null && operation.contains("INDEX") ? name : null,
                                rows.getString(7), rows.getString(8), rows.getString(10), details));
                    }
                }
            }
            StringBuilder raw = new StringBuilder();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', ?, 'ALL'))")) {
                statement.setString(1, id); control.active(statement);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) { control.checkCancelled(); raw.append(rows.getString(1)).append('\n'); }
                }
            }
            return new ExecutionPlan(sql, "oracle", raw.toString(), nodes,
                    nodes.isEmpty() ? "未获取到结构化计划，请查看原始文本" : "");
        } catch (SQLException e) { failure = e; throw e; }
        finally {
            control.cleanup();
            try {
                // Roll back only this operation; never commit or roll back the user's existing work.
                if (localTransactionStarted) { connection.rollback(); connection.setAutoCommit(true); }
                else if (savepoint != null) connection.rollback(savepoint);
            } catch (SQLException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }
}
