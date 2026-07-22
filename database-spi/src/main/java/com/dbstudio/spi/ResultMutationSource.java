package com.dbstudio.spi;

import java.util.Objects;

/** Physical table parsed from a query that is safe for generated row SQL. */
public final class ResultMutationSource {
    private final String catalog;
    private final String schema;
    private final String table;

    public ResultMutationSource(String catalog, String schema, String table) {
        this.catalog = catalog == null ? "" : catalog;
        this.schema = schema == null ? "" : schema;
        this.table = Objects.requireNonNull(table, "table");
    }

    public String catalog() { return catalog; }
    public String schema() { return schema; }
    public String table() { return table; }
}
