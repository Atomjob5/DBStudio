package com.dbstudio.mysql;

import com.dbstudio.spi.*;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.*;
import com.fasterxml.jackson.databind.*;
import java.sql.*;
import java.util.*;

public final class MySqlExecutionPlanAdapter implements ExecutionPlanAdapter {
    @Override public void validate(String sql) throws SQLException {
        try {
            List<SQLStatement> statements = SQLUtils.parseStatements(sql, "mysql");
            if (statements.size() != 1) throw new IllegalArgumentException();
            SQLStatement s = statements.get(0);
            if (!(s instanceof SQLSelectStatement || s instanceof SQLInsertStatement
                    || s instanceof SQLUpdateStatement || s instanceof SQLDeleteStatement)) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new SQLException("MySQL 执行计划仅支持单条 SELECT、INSERT、UPDATE 或 DELETE", e); }
    }

    @Override public ExecutionPlan explain(DatabaseSession session, String sql, Control control) throws SQLException {
        validate(sql);
        StringBuilder raw = new StringBuilder();
        try (Statement statement = session.jdbcConnection().createStatement()) {
            control.active(statement);
            try (ResultSet rows = statement.executeQuery("EXPLAIN FORMAT=JSON " + sql)) {
                while (rows.next()) { control.checkCancelled(); raw.append(rows.getString(1)); }
            }
        }
        return parse(sql, raw.toString());
    }

    public ExecutionPlan parse(String sql, String raw) {
        List<ExecutionPlan.Node> nodes = new ArrayList<ExecutionPlan.Node>();
        String warning = "";
        try {
            JsonNode root = new ObjectMapper().readTree(raw);
            if (root == null || !root.isObject() || !root.has("query_block")) throw new IllegalArgumentException();
            visit(root.get("query_block"), "query_block", null, nodes);
            if (nodes.isEmpty()) throw new IllegalArgumentException();
        } catch (Exception e) { nodes.clear(); warning = "无法完整解析此版本的计划格式，请查看原始文本"; }
        return new ExecutionPlan(sql, "mysql", raw, nodes, warning);
    }

    private void visit(JsonNode value, String operation, String parent, List<ExecutionPlan.Node> nodes) {
        if (value.isArray()) {
            String id = String.valueOf(nodes.size());
            nodes.add(new ExecutionPlan.Node(id, parent, operation, null, null, null, null, null, null,
                    Collections.<String, String>emptyMap()));
            for (JsonNode child : value) visitChildren(child, id, nodes);
            return;
        }
        if (!value.isObject()) return;
        String id = String.valueOf(nodes.size());
        Map<String, String> details = new LinkedHashMap<String, String>();
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isValueNode() || "cost_info".equals(field.getKey())
                    || "used_key_parts".equals(field.getKey())) details.put(field.getKey(), field.getValue().toString());
        }
        String cost = text(value.path("cost_info"), "query_cost");
        if (cost == null) cost = text(value.path("cost_info"), "prefix_cost");
        nodes.add(new ExecutionPlan.Node(id, parent, operation, text(value, "table_name"),
                text(value, "access_type"), text(value, "key"), text(value, "rows_examined_per_scan"), cost,
                text(value, "attached_condition"), details));
        visitChildren(value, id, nodes);
    }

    private void visitChildren(JsonNode value, String parent, List<ExecutionPlan.Node> nodes) {
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getValue().isContainerNode() && !Arrays.asList("cost_info", "used_columns", "possible_keys",
                    "used_key_parts", "ref", "partitions", "message").contains(field.getKey())) {
                visit(field.getValue(), field.getKey(), parent, nodes);
            }
        }
    }
    private static String text(JsonNode value, String key) {
        JsonNode child = value.get(key); return child == null || child.isNull() ? null : child.asText();
    }
}
