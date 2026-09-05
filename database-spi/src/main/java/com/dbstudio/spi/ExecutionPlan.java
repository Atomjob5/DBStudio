package com.dbstudio.spi;

import java.util.*;

/** A detached estimate; no live cursor or database handles are retained. */
public final class ExecutionPlan {
    private final String sql, providerId, rawText, warning;
    private final List<Node> nodes;
    public ExecutionPlan(String sql, String providerId, String rawText, List<Node> nodes, String warning) {
        this.sql = sql; this.providerId = providerId; this.rawText = rawText; this.warning = warning;
        this.nodes = Collections.unmodifiableList(new ArrayList<Node>(nodes));
    }
    public String getSql() { return sql; }
    public String getProviderId() { return providerId; }
    public String getRawText() { return rawText; }
    public String getWarning() { return warning; }
    public List<Node> getNodes() { return nodes; }

    public static final class Node {
        private final String id, parentId, operation, object, access, index, estimatedRows, cost, condition;
        private final Map<String, String> details;
        public Node(String id, String parentId, String operation, String object, String access, String index,
                    String estimatedRows, String cost, String condition, Map<String, String> details) {
            this.id = id; this.parentId = parentId; this.operation = operation; this.object = object;
            this.access = access; this.index = index; this.estimatedRows = estimatedRows;
            this.cost = cost; this.condition = condition;
            this.details = Collections.unmodifiableMap(new LinkedHashMap<String, String>(details));
        }
        public String getId() { return id; }
        public String getParentId() { return parentId; }
        public String getOperation() { return operation; }
        public String getObject() { return object; }
        public String getAccess() { return access; }
        public String getIndex() { return index; }
        public String getEstimatedRows() { return estimatedRows; }
        public String getCost() { return cost; }
        public String getCondition() { return condition; }
        public Map<String, String> getDetails() { return details; }
    }
}
