package com.dbstudio.desktop.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 仅用于生成安全剪贴板行 SQL 的不可变元数据。 */
public final class ResultMutationTarget {
    private final String qualifiedName;
    private final List<Column> columns;
    private final List<Key> uniqueKeys;
    private final boolean editableForUpdate;
    private final String mode;
    private final String reasonCode;
    private final String reason;
    private final String lockMode;
    private final boolean insertSupported;
    private final boolean deleteSupported;
    private final Locator locator;
    private final boolean emptyStringIsNull;
    private final String refreshSql;

    public ResultMutationTarget(String qualifiedName, List<Column> columns, List<Key> uniqueKeys) {
        this(qualifiedName, columns, uniqueKeys, false);
    }

    public ResultMutationTarget(String qualifiedName, List<Column> columns, List<Key> uniqueKeys,
                                boolean editableForUpdate) {
        this(qualifiedName, columns, uniqueKeys, editableForUpdate,
                editableForUpdate ? "editable" : "readOnly",
                editableForUpdate ? "" : "FOR_UPDATE_REQUIRED",
                editableForUpdate ? "" : "需要显式执行单表 FOR UPDATE 查询",
                editableForUpdate ? "WAIT" : "NONE", editableForUpdate, editableForUpdate);
    }

    public ResultMutationTarget(String qualifiedName, List<Column> columns, List<Key> uniqueKeys,
                                boolean editableForUpdate, String mode, String reasonCode, String reason,
                                String lockMode, boolean insertSupported, boolean deleteSupported) {
        this(qualifiedName, columns, uniqueKeys, editableForUpdate, mode, reasonCode, reason,
                lockMode, insertSupported, deleteSupported, null, false);
    }

    public ResultMutationTarget(String qualifiedName, List<Column> columns, List<Key> uniqueKeys,
                                boolean editableForUpdate, String mode, String reasonCode, String reason,
                                String lockMode, boolean insertSupported, boolean deleteSupported, Locator locator) {
        this(qualifiedName, columns, uniqueKeys, editableForUpdate, mode, reasonCode, reason,
                lockMode, insertSupported, deleteSupported, locator, false);
    }

    public ResultMutationTarget(String qualifiedName, List<Column> columns, List<Key> uniqueKeys,
                                boolean editableForUpdate, String mode, String reasonCode, String reason,
                                String lockMode, boolean insertSupported, boolean deleteSupported, Locator locator,
                                boolean emptyStringIsNull) {
        this(qualifiedName, columns, uniqueKeys, editableForUpdate, mode, reasonCode, reason,
                lockMode, insertSupported, deleteSupported, locator, emptyStringIsNull, "");
    }

    public ResultMutationTarget(String qualifiedName, List<Column> columns, List<Key> uniqueKeys,
                                boolean editableForUpdate, String mode, String reasonCode, String reason,
                                String lockMode, boolean insertSupported, boolean deleteSupported, Locator locator,
                                boolean emptyStringIsNull, String refreshSql) {
        this.qualifiedName = qualifiedName == null ? "" : qualifiedName;
        this.columns = immutable(columns);
        this.uniqueKeys = immutable(uniqueKeys);
        this.editableForUpdate = editableForUpdate;
        this.mode = value(mode, editableForUpdate ? "editable" : "readOnly");
        this.reasonCode = value(reasonCode, "");
        this.reason = value(reason, "");
        this.lockMode = value(lockMode, editableForUpdate ? "WAIT" : "NONE");
        this.insertSupported = editableForUpdate && insertSupported;
        this.deleteSupported = editableForUpdate && deleteSupported;
        this.locator = locator;
        this.emptyStringIsNull = emptyStringIsNull;
        this.refreshSql = refreshSql == null ? "" : refreshSql;
    }

    public String qualifiedName() { return qualifiedName; }
    public List<Column> columns() { return columns; }
    public List<Key> uniqueKeys() { return uniqueKeys; }
    public boolean editableForUpdate() { return editableForUpdate; }
    public String mode() { return mode; }
    public String reasonCode() { return reasonCode; }
    public String reason() { return reason; }
    public String lockMode() { return lockMode; }
    public boolean updateSupported() { return editableForUpdate; }
    public boolean insertSupported() { return insertSupported; }
    public boolean deleteSupported() { return deleteSupported; }
    public Locator locator() { return locator; }
    public boolean emptyStringIsNull() { return emptyStringIsNull; }
    /** Server-only query used to recompute projections and verify the row still matches the original filter. */
    public String refreshSql() { return refreshSql; }

    public ResultMutationTarget withLocator(Locator value) {
        return withLocator(value, "");
    }

    public ResultMutationTarget withLocator(Locator value, String rowRefreshSql) {
        boolean editable = editableForUpdate || value != null;
        boolean canInsert = insertSupported || value != null && "ROWID".equals(value.kind());
        return new ResultMutationTarget(qualifiedName, columns, uniqueKeys, editable,
                editable ? "editable" : mode, editable ? "" : reasonCode, editable ? "" : reason,
                lockMode, canInsert, editable, value, emptyStringIsNull, rowRefreshSql);
    }

    private static String value(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }

    public static final class Column {
        private final int resultIndex;
        private final String name;
        private final String quotedName;
        private final int jdbcType;
        private final String typeName;
        private final String typeFamily;
        private final int size;
        private final int scale;
        private final boolean nullable;
        private final String defaultValue;
        private final boolean autoIncrement;
        private final boolean generated;
        private final boolean editable;
        private final String readOnlyReason;
        private final List<String> enumValues;

        public Column(int resultIndex, String name, String quotedName, int jdbcType) {
            this(resultIndex, name, quotedName, jdbcType, "", family(jdbcType, ""), 0, 0,
                    true, "", false, false, supportsEditing(jdbcType, ""),
                    supportsEditing(jdbcType, "") ? "" : "该字段类型暂不支持直接编辑");
        }

        public Column(int resultIndex, String name, String quotedName, int jdbcType,
                      String typeName, String typeFamily, int size, int scale, boolean nullable,
                      String defaultValue, boolean autoIncrement, boolean generated,
                      boolean editable, String readOnlyReason) {
            this.resultIndex = resultIndex;
            this.name = name;
            this.quotedName = quotedName;
            this.jdbcType = jdbcType;
            this.typeName = typeName == null ? "" : typeName;
            this.typeFamily = value(typeFamily, family(jdbcType, typeName));
            this.size = size;
            this.scale = scale;
            this.nullable = nullable;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
            this.autoIncrement = autoIncrement;
            this.generated = generated;
            this.editable = editable && !generated;
            this.readOnlyReason = generated ? "生成列或虚拟列不可直接修改"
                    : value(readOnlyReason, this.editable ? "" : "该字段不可直接修改");
            this.enumValues = enumValues(this.typeName);
        }

        public int resultIndex() { return resultIndex; }
        public String name() { return name; }
        public String quotedName() { return quotedName; }
        public int jdbcType() { return jdbcType; }
        public String typeName() { return typeName; }
        public String typeFamily() { return typeFamily; }
        public int size() { return size; }
        public int scale() { return scale; }
        public boolean nullable() { return nullable; }
        public String defaultValue() { return defaultValue; }
        public boolean defaultAvailable() { return !defaultValue.isEmpty() || autoIncrement || generated; }
        public boolean autoIncrement() { return autoIncrement; }
        public boolean generated() { return generated; }
        public boolean editable() { return editable; }
        public String readOnlyReason() { return readOnlyReason; }
        public List<String> enumValues() { return enumValues; }

        private static List<String> enumValues(String typeName) {
            String text = typeName == null ? "" : typeName.trim();
            if (!text.toLowerCase(java.util.Locale.ROOT).startsWith("enum(")) return Collections.emptyList();
            List<String> result = new ArrayList<String>();
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("'((?:''|[^'])*)'").matcher(text);
            while (matcher.find()) result.add(matcher.group(1).replace("''", "'"));
            return Collections.unmodifiableList(result);
        }

        public static boolean supportsEditing(int jdbcType, String typeName) {
            if ("complex".equals(family(jdbcType, typeName))) return false;
            switch (jdbcType) {
                case java.sql.Types.ARRAY:
                case java.sql.Types.STRUCT:
                case java.sql.Types.REF:
                case java.sql.Types.ROWID:
                case java.sql.Types.SQLXML:
                case java.sql.Types.JAVA_OBJECT:
                case java.sql.Types.OTHER:
                    return false;
                default:
                    return true;
            }
        }

        private static String family(int jdbcType, String typeName) {
            String upper = typeName == null ? "" : typeName.toUpperCase(java.util.Locale.ROOT);
            if (upper.contains("JSON") || upper.contains("XML") || upper.contains("INTERVAL")
                    || upper.contains("VECTOR") || upper.contains("GEOMETRY")) return "complex";
            switch (jdbcType) {
                case java.sql.Types.TINYINT: case java.sql.Types.SMALLINT: case java.sql.Types.INTEGER:
                case java.sql.Types.BIGINT: case java.sql.Types.FLOAT: case java.sql.Types.REAL:
                case java.sql.Types.DOUBLE: case java.sql.Types.NUMERIC: case java.sql.Types.DECIMAL:
                    return "number";
                case java.sql.Types.BOOLEAN: case java.sql.Types.BIT: return "boolean";
                case java.sql.Types.DATE: return "date";
                case java.sql.Types.TIME: case java.sql.Types.TIME_WITH_TIMEZONE: return "time";
                case java.sql.Types.TIMESTAMP: case java.sql.Types.TIMESTAMP_WITH_TIMEZONE: return "timestamp";
                case java.sql.Types.BINARY: case java.sql.Types.VARBINARY: case java.sql.Types.LONGVARBINARY:
                    return "raw";
                case java.sql.Types.BLOB: return "blob";
                case java.sql.Types.CLOB: case java.sql.Types.NCLOB: return "clob";
                case java.sql.Types.ARRAY: case java.sql.Types.STRUCT: case java.sql.Types.REF:
                case java.sql.Types.ROWID: case java.sql.Types.SQLXML: case java.sql.Types.JAVA_OBJECT:
                case java.sql.Types.OTHER: return "complex";
                default: return "text";
            }
        }
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

    public static final class Locator {
        private final String kind;
        private final List<String> predicates;
        private final List<Integer> jdbcTypes;
        private final List<String> columnNames;
        public Locator(String kind, List<String> predicates, List<Integer> jdbcTypes) {
            this(kind, predicates, jdbcTypes, Collections.<String>emptyList());
        }
        public Locator(String kind, List<String> predicates, List<Integer> jdbcTypes,
                       List<String> columnNames) {
            this.kind = value(kind, "UNIQUE_KEY");
            this.predicates = immutable(predicates);
            this.jdbcTypes = immutable(jdbcTypes);
            this.columnNames = immutable(columnNames);
        }
        public String kind() { return kind; }
        public List<String> predicates() { return predicates; }
        public List<Integer> jdbcTypes() { return jdbcTypes; }
        public List<String> columnNames() { return columnNames; }
    }
}
