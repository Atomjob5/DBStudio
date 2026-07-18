package com.dbstudio.desktop.query;

/** Immutable JDBC result column metadata. Index is implicit in the containing list. */
public final class ResultColumn {
    private final String label;
    private final String name;
    private final String catalog;
    private final String schema;
    private final String table;
    private final String typeName;
    private final String remarks;

    public ResultColumn(String label, String name, String catalog, String schema,
                        String table, String typeName, String remarks) {
        this.label = value(label);
        this.name = value(name);
        this.catalog = value(catalog);
        this.schema = value(schema);
        this.table = value(table);
        this.typeName = value(typeName);
        this.remarks = value(remarks);
    }

    public String label() { return label; }
    public String name() { return name; }
    public String catalog() { return catalog; }
    public String schema() { return schema; }
    public String table() { return table; }
    public String typeName() { return typeName; }
    public String remarks() { return remarks; }

    public ResultColumn withRemarks(String value) {
        return new ResultColumn(label, name, catalog, schema, table, typeName, value);
    }

    public ResultColumn withName(String value) {
        return new ResultColumn(label, value, catalog, schema, table, typeName, remarks);
    }

    private static String value(String text) { return text == null ? "" : text; }
}
