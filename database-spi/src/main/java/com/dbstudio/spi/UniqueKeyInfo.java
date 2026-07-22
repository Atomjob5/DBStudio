package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable primary or unique key metadata in database ordinal order. */
public final class UniqueKeyInfo {
    private final String name;
    private final boolean primary;
    private final List<String> columns;

    public UniqueKeyInfo(String name, boolean primary, List<String> columns) {
        this.name = name == null ? "" : name;
        this.primary = primary;
        this.columns = columns == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(columns));
    }

    public String name() { return name; }
    public boolean primary() { return primary; }
    public List<String> columns() { return columns; }

    @Override public boolean equals(Object value) {
        if (this == value) return true;
        if (!(value instanceof UniqueKeyInfo)) return false;
        UniqueKeyInfo other = (UniqueKeyInfo) value;
        return primary == other.primary && name.equals(other.name) && columns.equals(other.columns);
    }

    @Override public int hashCode() { return Objects.hash(name, primary, columns); }
}
