package com.dbstudio.spi;

public final class ObjectPartitionInfo {
    private final String id;
    private final String name;
    private final String parentName;
    private final int position;
    private final String method;
    private final String expression;
    private final String boundary;
    private final String tablespace;
    private final Long estimatedRows;
    private final Long dataBytes;
    private final boolean hasSubpartitions;

    public ObjectPartitionInfo(String id, String name, String parentName, int position, String method,
                               String expression, String boundary, String tablespace, Long estimatedRows,
                               Long dataBytes, boolean hasSubpartitions) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.parentName = parentName == null ? "" : parentName;
        this.position = position;
        this.method = method == null ? "" : method;
        this.expression = expression == null ? "" : expression;
        this.boundary = boundary == null ? "" : boundary;
        this.tablespace = tablespace == null ? "" : tablespace;
        this.estimatedRows = estimatedRows;
        this.dataBytes = dataBytes;
        this.hasSubpartitions = hasSubpartitions;
    }
    public String id() { return id; }
    public String name() { return name; }
    public String parentName() { return parentName; }
    public int position() { return position; }
    public String method() { return method; }
    public String expression() { return expression; }
    public String boundary() { return boundary; }
    public String tablespace() { return tablespace; }
    public Long estimatedRows() { return estimatedRows; }
    public Long dataBytes() { return dataBytes; }
    public boolean hasSubpartitions() { return hasSubpartitions; }
}
