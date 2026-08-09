package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ObjectIndexInfo {
    private final String name;
    private final boolean primary;
    private final boolean unique;
    private final String type;
    private final String status;
    private final boolean visible;
    private final boolean partitioned;
    private final String tablespace;
    private final List<ObjectIndexColumnInfo> columns;

    public ObjectIndexInfo(String name, boolean primary, boolean unique, String type, String status,
                           boolean visible, boolean partitioned, String tablespace,
                           List<ObjectIndexColumnInfo> columns) {
        this.name = name == null ? "" : name;
        this.primary = primary;
        this.unique = unique;
        this.type = type == null ? "" : type;
        this.status = status == null ? "" : status;
        this.visible = visible;
        this.partitioned = partitioned;
        this.tablespace = tablespace == null ? "" : tablespace;
        this.columns = Collections.unmodifiableList(new ArrayList<ObjectIndexColumnInfo>(columns));
    }
    public String name() { return name; }
    public boolean primary() { return primary; }
    public boolean unique() { return unique; }
    public String type() { return type; }
    public String status() { return status; }
    public boolean visible() { return visible; }
    public boolean partitioned() { return partitioned; }
    public String tablespace() { return tablespace; }
    public List<ObjectIndexColumnInfo> columns() { return columns; }
}
