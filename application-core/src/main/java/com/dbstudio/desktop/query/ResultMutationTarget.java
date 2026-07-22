package com.dbstudio.desktop.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 仅用于生成安全剪贴板行 SQL 的不可变元数据。 */
public final class ResultMutationTarget {
    private final String qualifiedName;
    private final List<Column> columns;
    private final List<Key> uniqueKeys;

    public ResultMutationTarget(String qualifiedName, List<Column> columns, List<Key> uniqueKeys) {
        this.qualifiedName = qualifiedName == null ? "" : qualifiedName;
        this.columns = immutable(columns);
        this.uniqueKeys = immutable(uniqueKeys);
    }

    public String qualifiedName() { return qualifiedName; }
    public List<Column> columns() { return columns; }
    public List<Key> uniqueKeys() { return uniqueKeys; }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }

    public static final class Column {
        private final int resultIndex;
        private final String name;
        private final String quotedName;
        private final int jdbcType;

        public Column(int resultIndex, String name, String quotedName, int jdbcType) {
            this.resultIndex = resultIndex;
            this.name = name;
            this.quotedName = quotedName;
            this.jdbcType = jdbcType;
        }

        public int resultIndex() { return resultIndex; }
        public String name() { return name; }
        public String quotedName() { return quotedName; }
        public int jdbcType() { return jdbcType; }
    }

    public static final class Key {
        private final String name;
        private final boolean primary;
        private final List<Integer> resultColumnIndices;

        public Key(String name, boolean primary, List<Integer> resultColumnIndices) {
            this.name = name == null ? "" : name;
            this.primary = primary;
            this.resultColumnIndices = immutable(resultColumnIndices);
        }

        public String name() { return name; }
        public boolean primary() { return primary; }
        public List<Integer> resultColumnIndices() { return resultColumnIndices; }
    }
}
