package com.dbstudio.spi;

import java.util.Objects;

/** 从查询中安全解析出的物理表来源，用于生成行级 SQL。 */
public final class ResultMutationSource {
    private final String catalog;
    private final String schema;
    private final String table;
    private final String alias;
    private final boolean editableForUpdate;

    public ResultMutationSource(String catalog, String schema, String table) {
        this(catalog, schema, table, false);
    }

    public ResultMutationSource(String catalog, String schema, String table, boolean editableForUpdate) {
        this(catalog, schema, table, "", editableForUpdate);
    }

    public ResultMutationSource(String catalog, String schema, String table, String alias,
                                boolean editableForUpdate) {
        this.catalog = catalog == null ? "" : catalog;
        this.schema = schema == null ? "" : schema;
        this.table = Objects.requireNonNull(table, "table");
        this.alias = alias == null ? "" : alias;
        this.editableForUpdate = editableForUpdate;
    }

    public String catalog() { return catalog; }
    public String schema() { return schema; }
    public String table() { return table; }
    public String alias() { return alias; }
    public boolean editableForUpdate() { return editableForUpdate; }
}
