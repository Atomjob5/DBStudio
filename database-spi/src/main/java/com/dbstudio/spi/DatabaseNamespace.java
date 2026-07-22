package com.dbstudio.spi;

import java.util.Objects;

/** A database object namespace. MySQL uses catalogs; Oracle-compatible providers use schemas. */
public final class DatabaseNamespace {
    private final String catalog;
    private final String schema;
    private final String label;
    private final NamespaceKind kind;
    private final boolean current;

    public DatabaseNamespace(String catalog, String schema, String label,
                             NamespaceKind kind, boolean current) {
        this.catalog = catalog == null ? "" : catalog;
        this.schema = schema == null ? "" : schema;
        this.label = Objects.requireNonNull(label, "label");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.current = current;
    }

    public static DatabaseNamespace catalog(String name, boolean current) {
        return new DatabaseNamespace(name, "", name, NamespaceKind.CATALOG, current);
    }

    public static DatabaseNamespace schema(String name, boolean current) {
        return new DatabaseNamespace("", name, name, NamespaceKind.SCHEMA, current);
    }

    public String catalog() { return catalog; }
    public String schema() { return schema; }
    public String label() { return label; }
    public NamespaceKind kind() { return kind; }
    public boolean current() { return current; }
}
