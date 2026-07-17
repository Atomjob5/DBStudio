package com.dbstudio.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DatabaseObject {
    private final DatabaseObjectType type;
    private final String catalog;
    private final String schema;
    private final String name;
    private final String remarks;
    private final Map<String, String> attributes;

    public DatabaseObject(DatabaseObjectType type, String catalog, String schema, String name,
                          String remarks, Map<String, String> attributes) {
        this.type = Objects.requireNonNull(type, "type");
        this.catalog = catalog == null ? "" : catalog;
        this.schema = schema == null ? "" : schema;
        this.name = Objects.requireNonNull(name, "name");
        this.remarks = remarks == null ? "" : remarks;
        this.attributes = attributes == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(attributes));
    }

    public DatabaseObjectType type() { return type; }
    public String catalog() { return catalog; }
    public String schema() { return schema; }
    public String name() { return name; }
    public String remarks() { return remarks; }
    public Map<String, String> attributes() { return attributes; }

    @Override
    public String toString() { return name; }
}
