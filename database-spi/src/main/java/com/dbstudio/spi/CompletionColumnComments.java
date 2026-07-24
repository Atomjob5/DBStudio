package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded group of completion column comments belonging to one physical object. */
public final class CompletionColumnComments {
    private final String catalog;
    private final String schema;
    private final String objectName;
    private final List<ColumnInfo> columns;

    public CompletionColumnComments(String catalog, String schema, String objectName,
                                    List<ColumnInfo> columns) {
        this.catalog = catalog == null ? "" : catalog;
        this.schema = schema == null ? "" : schema;
        this.objectName = objectName == null ? "" : objectName;
        this.columns = Collections.unmodifiableList(new ArrayList<ColumnInfo>(columns));
    }

    public String catalog() { return catalog; }
    public String schema() { return schema; }
    public String objectName() { return objectName; }
    public List<ColumnInfo> columns() { return columns; }
}
