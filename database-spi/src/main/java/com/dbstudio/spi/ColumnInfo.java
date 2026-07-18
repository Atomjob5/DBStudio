package com.dbstudio.spi;

import java.util.Objects;

public final class ColumnInfo {
    private final String name;
    private final String typeName;
    private final int size;
    private final int scale;
    private final boolean nullable;
    private final String defaultValue;
    private final boolean primaryKey;
    private final int ordinal;
    private final String remarks;

    public ColumnInfo(String name, String typeName, int size, int scale, boolean nullable,
                      String defaultValue, boolean primaryKey, int ordinal) {
        this(name, typeName, size, scale, nullable, defaultValue, primaryKey, ordinal, "");
    }

    public ColumnInfo(String name, String typeName, int size, int scale, boolean nullable,
                      String defaultValue, boolean primaryKey, int ordinal, String remarks) {
        this.name = name;
        this.typeName = typeName;
        this.size = size;
        this.scale = scale;
        this.nullable = nullable;
        this.defaultValue = defaultValue;
        this.primaryKey = primaryKey;
        this.ordinal = ordinal;
        this.remarks = remarks == null ? "" : remarks;
    }

    public String name() { return name; }
    public String typeName() { return typeName; }
    public int size() { return size; }
    public int scale() { return scale; }
    public boolean nullable() { return nullable; }
    public String defaultValue() { return defaultValue; }
    public boolean primaryKey() { return primaryKey; }
    public int ordinal() { return ordinal; }
    public String remarks() { return remarks; }

    @Override
    public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof ColumnInfo)) return false;
        ColumnInfo other = (ColumnInfo) value;
        return size == other.size && scale == other.scale && nullable == other.nullable
                && primaryKey == other.primaryKey && ordinal == other.ordinal
                && Objects.equals(name, other.name) && Objects.equals(typeName, other.typeName)
                && Objects.equals(defaultValue, other.defaultValue) && Objects.equals(remarks, other.remarks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, typeName, size, scale, nullable, defaultValue, primaryKey, ordinal, remarks);
    }
}
