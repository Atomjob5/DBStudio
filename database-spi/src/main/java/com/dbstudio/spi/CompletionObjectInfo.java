package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Object and columns returned as one metadata unit for full completion snapshots. */
public final class CompletionObjectInfo {
    private final DatabaseObject object;
    private final List<ColumnInfo> columns;

    public CompletionObjectInfo(DatabaseObject object, List<ColumnInfo> columns) {
        this.object = Objects.requireNonNull(object, "object");
        this.columns = columns == null ? Collections.<ColumnInfo>emptyList()
                : Collections.unmodifiableList(new ArrayList<ColumnInfo>(columns));
    }

    public DatabaseObject object() { return object; }
    public List<ColumnInfo> columns() { return columns; }
}
