package com.dbstudio.oceanbase.oracle;

import com.dbstudio.spi.*;
import com.dbstudio.oracle.common.PlanValidation;
import java.sql.*;
import java.util.*;

public final class OceanBaseExecutionPlanAdapter implements ExecutionPlanAdapter {
    @Override public void validate(String sql) throws SQLException { PlanValidation.validate(sql); }
    @Override public ExecutionPlan explain(DatabaseSession session, String sql, Control control) throws SQLException {
        validate(sql);
        StringBuilder raw = new StringBuilder();
        try (Statement statement = session.jdbcConnection().createStatement()) {
            control.active(statement);
            try (ResultSet rows = statement.executeQuery("EXPLAIN EXTENDED " + sql)) {
                while (rows.next()) { control.checkCancelled(); raw.append(rows.getString(1)).append('\n'); }
            }
        }
        return parse(sql, raw.toString());
    }

    public ExecutionPlan parse(String sql, String raw) {
        List<ExecutionPlan.Node> nodes = new ArrayList<ExecutionPlan.Node>();
        List<Integer> depths = new ArrayList<Integer>();
        List<String> parents = new ArrayList<String>();
        Map<String, Integer> headers = new HashMap<String, Integer>();
        String warning = "";
        try {
            for (String line : raw.split("\\r?\\n")) {
                if (!line.startsWith("|")) continue;
                String[] cells = line.split("\\|", -1);
                if (line.contains("OPERATOR") && line.contains("ID")) {
                    for (int i = 1; i < cells.length - 1; i++) headers.put(cells[i].trim(), i);
                    continue;
                }
                if (headers.isEmpty() || !cell(cells, headers, "ID").matches("\\d+")) continue;
                String id = cell(cells, headers, "ID");
                String op = cells[headers.get("OPERATOR")];
                int depth = op.length() - op.replaceFirst("^ +", "").length();
                while (!depths.isEmpty() && depths.get(depths.size() - 1) >= depth) {
                    depths.remove(depths.size() - 1); parents.remove(parents.size() - 1);
                }
                if (!nodes.isEmpty() && parents.isEmpty()) throw new IllegalArgumentException();
                String parent = parents.isEmpty() ? null : parents.get(parents.size() - 1);
                Map<String, String> details = new LinkedHashMap<String, String>();
                details.put("原始算子行", line);
                nodes.add(new ExecutionPlan.Node(id, parent, op.trim(), cell(cells, headers, "NAME"), null, null,
                        cell(cells, headers, "EST.ROWS"), cell(cells, headers, "COST"), null, details));
                depths.add(depth); parents.add(id);
            }
            if (nodes.isEmpty()) throw new IllegalArgumentException();
        } catch (RuntimeException e) { nodes.clear(); warning = "无法完整解析此版本的计划格式，请查看原始文本"; }
        // EXTENDED appends per-operator output/filter/range information after the table.
        Map<String, StringBuilder> sections = new LinkedHashMap<String, StringBuilder>();
        StringBuilder current = null;
        for (String line : raw.split("\\r?\\n")) {
            java.util.regex.Matcher heading = java.util.regex.Pattern.compile("^\\s*(\\d+)\\s*-\\s*(.*)$").matcher(line);
            if (heading.matches()) {
                current = new StringBuilder(heading.group(2)); sections.put(heading.group(1), current);
            } else if (current != null && !line.startsWith("|")) current.append('\n').append(line);
        }
        List<ExecutionPlan.Node> enriched = new ArrayList<ExecutionPlan.Node>();
        for (ExecutionPlan.Node node : nodes) {
            Map<String, String> details = new LinkedHashMap<String, String>(node.getDetails());
            String condition = null;
            StringBuilder section = sections.get(node.getId());
            if (section != null) {
                String value = section.toString().trim(); details.put("算子附加信息", value);
                int filter = value.indexOf("filter(");
                if (filter >= 0) {
                    int depth = 1, end = filter + 7;
                    for (; end < value.length() && depth > 0; end++) {
                        if (value.charAt(end) == '(') depth++;
                        if (value.charAt(end) == ')') depth--;
                    }
                    if (depth == 0) condition = value.substring(filter + 7, end - 1);
                }
            }
            enriched.add(new ExecutionPlan.Node(node.getId(), node.getParentId(), node.getOperation(), node.getObject(),
                    node.getAccess(), node.getIndex(), node.getEstimatedRows(), node.getCost(), condition, details));
        }
        return new ExecutionPlan(sql, "oceanbase-oracle", raw, enriched, warning);
    }
    private static String cell(String[] cells, Map<String, Integer> headers, String name) {
        Integer index = headers.get(name);
        return index == null || index >= cells.length ? "" : cells[index].trim();
    }
}
