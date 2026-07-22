package com.dbstudio.desktop.query;

/** 不可变的 JDBC 结果列元数据，列索引由所在列表的顺序隐含表达。 */
public final class ResultColumn {
    private final String label;
    private final String name;
    private final String catalog;
    private final String schema;
    private final String table;
    private final String typeName;
    private final String remarks;
    private final int jdbcType;
    private final String quotedLabel;

    public ResultColumn(String label, String name, String catalog, String schema,
                        String table, String typeName, String remarks) {
        this(label, name, catalog, schema, table, typeName, remarks, java.sql.Types.VARCHAR, label);
    }

    public ResultColumn(String label, String name, String catalog, String schema,
                        String table, String typeName, String remarks, int jdbcType, String quotedLabel) {
        this.label = value(label);
        this.name = value(name);
        this.catalog = value(catalog);
        this.schema = value(schema);
        this.table = value(table);
        this.typeName = value(typeName);
        this.remarks = value(remarks);
        this.jdbcType = jdbcType;
        this.quotedLabel = value(quotedLabel);
    }

    public String label() { return label; }
    public String name() { return name; }
    public String catalog() { return catalog; }
    public String schema() { return schema; }
    public String table() { return table; }
    public String typeName() { return typeName; }
    public String remarks() { return remarks; }
    public int jdbcType() { return jdbcType; }
    public String quotedLabel() { return quotedLabel; }

    public ResultColumn withRemarks(String value) {
        return new ResultColumn(label, name, catalog, schema, table, typeName, value, jdbcType, quotedLabel);
    }

    public ResultColumn withName(String value) {
        return new ResultColumn(label, value, catalog, schema, table, typeName, remarks, jdbcType, quotedLabel);
    }

    public ResultColumn withQuotedLabel(String value) {
        return new ResultColumn(label, name, catalog, schema, table, typeName, remarks, jdbcType, value);
    }

    private static String value(String text) { return text == null ? "" : text; }
}
