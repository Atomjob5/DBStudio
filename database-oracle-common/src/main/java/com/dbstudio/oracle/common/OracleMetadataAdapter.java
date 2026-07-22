package com.dbstudio.oracle.common;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.CompletionObjectInfo;
import com.dbstudio.spi.DatabaseNamespace;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.UniqueKeyInfo;
import java.io.IOException;
import java.io.Reader;
import java.sql.Clob;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Metadata implementation shared by native Oracle and OceanBase Oracle mode. */
public class OracleMetadataAdapter implements MetadataAdapter {
    private static final Set<String> SYSTEM_SCHEMAS = Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
            "SYS", "SYSTEM", "SYSAUX", "OUTLN", "DBSNMP", "APPQOSSYS", "AUDSYS", "CTXSYS", "DVSYS",
            "GSMADMIN_INTERNAL", "LBACSYS", "MDSYS", "OJVMSYS", "OLAPSYS", "ORDDATA", "ORDSYS", "WMSYS",
            "XDB", "ANONYMOUS", "APEX_PUBLIC_USER", "DIP", "FLOWS_FILES", "ORACLE_OCM", "PUBLIC")));

    @Override public List<DatabaseNamespace> listNamespaces(DatabaseSession session) throws SQLException {
        String current = upper(session.currentSchema());
        Set<String> names = new LinkedHashSet<String>();
        try (Statement statement = session.jdbcConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT DISTINCT OWNER FROM ALL_OBJECTS ORDER BY OWNER")) {
            while (result.next()) addSchema(names, result.getString(1));
        } catch (SQLException dictionaryFailure) {
            try (ResultSet result = session.jdbcConnection().getMetaData().getSchemas()) {
                while (result.next()) addSchema(names, result.getString("TABLE_SCHEM"));
            }
        }
        if (!current.isEmpty() && !isSystemSchema(current)) names.add(current);
        List<DatabaseNamespace> result = new ArrayList<DatabaseNamespace>();
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        if (sorted.remove(current)) sorted.add(0, current);
        for (String name : sorted) result.add(DatabaseNamespace.schema(name, name.equalsIgnoreCase(current)));
        return Collections.unmodifiableList(result);
    }

    @Override public List<DatabaseObject> listObjects(DatabaseSession session, DatabaseNamespace namespace,
                                                       DatabaseObjectType type) throws SQLException {
        String schema = requiredSchema(namespace);
        switch (type) {
            case TABLE: return namedWithComments(session, schema, type, "TABLE", "TABLE");
            case VIEW: return namedWithComments(session, schema, type, "VIEW", "VIEW");
            case INDEX: return queryObjects(session, schema, type,
                    "SELECT INDEX_NAME, TABLE_NAME FROM ALL_INDEXES WHERE OWNER=? ORDER BY INDEX_NAME", "table");
            case CONSTRAINT: return queryObjects(session, schema, type,
                    "SELECT CONSTRAINT_NAME, TABLE_NAME FROM ALL_CONSTRAINTS WHERE OWNER=? ORDER BY CONSTRAINT_NAME", "table");
            case TRIGGER: return queryObjects(session, schema, type,
                    "SELECT TRIGGER_NAME, TABLE_NAME FROM ALL_TRIGGERS WHERE OWNER=? ORDER BY TRIGGER_NAME", "table");
            case SEQUENCE: return queryObjects(session, schema, type,
                    "SELECT SEQUENCE_NAME, NULL FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER=? ORDER BY SEQUENCE_NAME", "");
            case SYNONYM: return queryObjects(session, schema, type,
                    "SELECT SYNONYM_NAME, TABLE_OWNER || '.' || TABLE_NAME FROM ALL_SYNONYMS WHERE OWNER=? ORDER BY SYNONYM_NAME", "target");
            case PROCEDURE: return dictionaryObjects(session, schema, type, "PROCEDURE");
            case FUNCTION: return dictionaryObjects(session, schema, type, "FUNCTION");
            case PACKAGE: return dictionaryObjects(session, schema, type, "PACKAGE");
            case TYPE: return dictionaryObjects(session, schema, type, "TYPE");
            default: return Collections.emptyList();
        }
    }

    @Override public List<DatabaseObject> listObjects(DatabaseSession session, String catalog,
                                                       DatabaseObjectType type) throws SQLException {
        return listObjects(session, DatabaseNamespace.schema(catalog, false), type);
    }

    @Override public List<CompletionObjectInfo> listCompletionObjects(DatabaseSession session,
                                                                       List<DatabaseNamespace> namespaces,
                                                                       Set<DatabaseObjectType> types)
            throws SQLException {
        List<DatabaseObject> objects = new ArrayList<DatabaseObject>();
        for (DatabaseNamespace namespace : namespaces) {
            for (DatabaseObjectType type : types) objects.addAll(listObjects(session, namespace, type));
        }
        Map<String, List<ColumnInfo>> columns = loadCompletionColumns(session, namespaces);
        List<CompletionObjectInfo> result = new ArrayList<CompletionObjectInfo>(objects.size());
        for (DatabaseObject object : objects) {
            List<ColumnInfo> values = columns.get(objectKey(object.schema(), object.name()));
            result.add(new CompletionObjectInfo(object,
                    values == null ? Collections.<ColumnInfo>emptyList() : values));
        }
        return Collections.unmodifiableList(result);
    }

    @Override public List<ColumnInfo> listColumns(DatabaseSession session, String catalog, String schema,
                                                   String objectName) throws SQLException {
        String owner = schema == null || schema.trim().isEmpty() ? catalog : schema;
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        Set<String> primary = primaryColumns(session, owner, objectName);
        try (ResultSet result = session.jdbcConnection().getMetaData().getColumns(null, owner, objectName, "%")) {
            while (result.next()) {
                String name = result.getString("COLUMN_NAME");
                columns.add(new ColumnInfo(name, result.getString("TYPE_NAME"), result.getInt("COLUMN_SIZE"),
                        result.getInt("DECIMAL_DIGITS"), result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        result.getString("COLUMN_DEF"), containsIgnoreCase(primary, name),
                        result.getInt("ORDINAL_POSITION"), value(result.getString("REMARKS"))));
            }
        }
        Collections.sort(columns, new Comparator<ColumnInfo>() {
            @Override public int compare(ColumnInfo left, ColumnInfo right) {
                return Integer.compare(left.ordinal(), right.ordinal());
            }
        });
        return Collections.unmodifiableList(columns);
    }

    @Override public boolean isBaseTable(DatabaseSession session, String catalog, String schema,
                                          String objectName) throws SQLException {
        String owner = schema == null || schema.trim().isEmpty() ? catalog : schema;
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT 1 FROM ALL_TABLES WHERE OWNER=? AND TABLE_NAME=?")) {
            statement.setString(1, upper(owner)); statement.setString(2, upper(objectName));
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    @Override public List<UniqueKeyInfo> listUniqueKeys(DatabaseSession session, String catalog, String schema,
                                                        String objectName) throws SQLException {
        String owner = schema == null || schema.trim().isEmpty() ? catalog : schema;
        Map<String, KeyBuilder> keys = new LinkedHashMap<String, KeyBuilder>();
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT c.CONSTRAINT_NAME,c.CONSTRAINT_TYPE,cc.COLUMN_NAME,cc.POSITION "
                        + "FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS cc ON cc.OWNER=c.OWNER "
                        + "AND cc.CONSTRAINT_NAME=c.CONSTRAINT_NAME AND cc.TABLE_NAME=c.TABLE_NAME "
                        + "WHERE c.OWNER=? AND c.TABLE_NAME=? AND c.CONSTRAINT_TYPE IN ('P','U') "
                        + "ORDER BY CASE c.CONSTRAINT_TYPE WHEN 'P' THEN 0 ELSE 1 END,c.CONSTRAINT_NAME,cc.POSITION")) {
            statement.setString(1, upper(owner)); statement.setString(2, upper(objectName));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String name = result.getString(1);
                    KeyBuilder key = keys.get(name);
                    if (key == null) { key = new KeyBuilder(name, "P".equals(result.getString(2))); keys.put(name, key); }
                    key.columns.put(result.getInt(4), result.getString(3));
                }
            }
        }
        List<UniqueKeyInfo> result = new ArrayList<UniqueKeyInfo>();
        for (KeyBuilder key : keys.values()) result.add(new UniqueKeyInfo(key.name, key.primary,
                new ArrayList<String>(key.columns.values())));
        return Collections.unmodifiableList(result);
    }

    @Override public String definition(DatabaseSession session, DatabaseObject object) throws SQLException {
        String ddlType = ddlType(object.type());
        if (!ddlType.isEmpty()) {
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                    "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL")) {
                statement.setString(1, ddlType); statement.setString(2, upper(object.name()));
                statement.setString(3, upper(object.schema()));
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) return text(result.getObject(1));
                }
            } catch (SQLException ignored) { /* Fall through to ALL_SOURCE for program units. */ }
        }
        if (Arrays.asList(DatabaseObjectType.PROCEDURE, DatabaseObjectType.FUNCTION,
                DatabaseObjectType.PACKAGE, DatabaseObjectType.TYPE, DatabaseObjectType.TRIGGER).contains(object.type())) {
            StringBuilder source = new StringBuilder();
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                    "SELECT TEXT FROM ALL_SOURCE WHERE OWNER=? AND NAME=? ORDER BY TYPE,LINE")) {
                statement.setString(1, upper(object.schema())); statement.setString(2, upper(object.name()));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) source.append(result.getString(1));
                }
            }
            if (source.length() > 0) return source.toString();
        }
        throw new SQLException("无法读取 " + object.schema() + "." + object.name()
                + " 的定义，请确认数据字典访问权限");
    }

    protected boolean isSystemSchema(String schema) { return SYSTEM_SCHEMAS.contains(upper(schema)); }

    private Map<String, List<ColumnInfo>> loadCompletionColumns(DatabaseSession session,
                                                                 List<DatabaseNamespace> namespaces)
            throws SQLException {
        List<String> schemas = new ArrayList<String>();
        for (DatabaseNamespace namespace : namespaces) {
            String schema = requiredSchema(namespace);
            if (!schemas.contains(schema)) schemas.add(schema);
        }
        Map<String, List<ColumnInfo>> result = new LinkedHashMap<String, List<ColumnInfo>>();
        final int chunkSize = 500;
        for (int start = 0; start < schemas.size(); start += chunkSize) {
            List<String> chunk = schemas.subList(start, Math.min(schemas.size(), start + chunkSize));
            StringBuilder placeholders = new StringBuilder();
            for (int index = 0; index < chunk.size(); index++) {
                if (index > 0) placeholders.append(',');
                placeholders.append('?');
            }
            String sql = "SELECT c.OWNER,c.TABLE_NAME,c.COLUMN_NAME,c.DATA_TYPE,"
                    + "COALESCE(c.DATA_PRECISION,c.DATA_LENGTH),c.DATA_SCALE,c.NULLABLE,"
                    + "c.COLUMN_ID,cc.COMMENTS,CASE WHEN pk.COLUMN_NAME IS NULL THEN 0 ELSE 1 END "
                    + "FROM ALL_TAB_COLUMNS c LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER=c.OWNER "
                    + "AND cc.TABLE_NAME=c.TABLE_NAME AND cc.COLUMN_NAME=c.COLUMN_NAME LEFT JOIN ("
                    + "SELECT kc.OWNER,kc.TABLE_NAME,kc.COLUMN_NAME FROM ALL_CONSTRAINTS k "
                    + "JOIN ALL_CONS_COLUMNS kc ON kc.OWNER=k.OWNER AND kc.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
                    + "AND kc.TABLE_NAME=k.TABLE_NAME WHERE k.CONSTRAINT_TYPE='P') pk ON pk.OWNER=c.OWNER "
                    + "AND pk.TABLE_NAME=c.TABLE_NAME AND pk.COLUMN_NAME=c.COLUMN_NAME WHERE c.OWNER IN ("
                    + placeholders + ") ORDER BY c.OWNER,c.TABLE_NAME,c.COLUMN_ID";
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
                for (int index = 0; index < chunk.size(); index++) statement.setString(index + 1, chunk.get(index));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String key = objectKey(rows.getString(1), rows.getString(2));
                        List<ColumnInfo> values = result.get(key);
                        if (values == null) { values = new ArrayList<ColumnInfo>(); result.put(key, values); }
                        values.add(new ColumnInfo(rows.getString(3), rows.getString(4), rows.getInt(5),
                                rows.getInt(6), "Y".equalsIgnoreCase(rows.getString(7)), "",
                                rows.getInt(10) == 1, rows.getInt(8), value(rows.getString(9))));
                    }
                }
            }
        }
        Map<String, List<ColumnInfo>> immutable = new LinkedHashMap<String, List<ColumnInfo>>();
        for (Map.Entry<String, List<ColumnInfo>> entry : result.entrySet()) {
            immutable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    private static String objectKey(String schema, String objectName) {
        return upper(schema) + '\u0000' + upper(objectName);
    }

    private List<DatabaseObject> namedWithComments(DatabaseSession session, String schema,
                                                    DatabaseObjectType type, String objectType,
                                                    String tableType) throws SQLException {
        List<DatabaseObject> result = new ArrayList<DatabaseObject>();
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT o.OBJECT_NAME,c.COMMENTS FROM ALL_OBJECTS o LEFT JOIN ALL_TAB_COMMENTS c "
                        + "ON c.OWNER=o.OWNER AND c.TABLE_NAME=o.OBJECT_NAME AND c.TABLE_TYPE=? "
                        + "WHERE o.OWNER=? AND o.OBJECT_TYPE=? ORDER BY o.OBJECT_NAME")) {
            statement.setString(1, tableType); statement.setString(2, schema); statement.setString(3, objectType);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(object(type, schema, rows.getString(1), value(rows.getString(2)),
                        Collections.<String, String>emptyMap()));
            }
        }
        return result;
    }

    private List<DatabaseObject> dictionaryObjects(DatabaseSession session, String schema,
                                                    DatabaseObjectType type, String dictionaryType) throws SQLException {
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT OBJECT_NAME,NULL FROM ALL_OBJECTS WHERE OWNER=? AND OBJECT_TYPE=? ORDER BY OBJECT_NAME")) {
            statement.setString(1, schema); statement.setString(2, dictionaryType);
            return readObjects(statement, schema, type, "");
        }
    }

    private List<DatabaseObject> queryObjects(DatabaseSession session, String schema, DatabaseObjectType type,
                                               String sql, String attribute) throws SQLException {
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
            statement.setString(1, schema);
            return readObjects(statement, schema, type, attribute);
        }
    }

    private List<DatabaseObject> readObjects(PreparedStatement statement, String schema,
                                             DatabaseObjectType type, String attribute) throws SQLException {
        List<DatabaseObject> result = new ArrayList<DatabaseObject>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Map<String, String> attributes = new LinkedHashMap<String, String>();
                String detail = value(rows.getString(2));
                if (!attribute.isEmpty() && !detail.isEmpty()) attributes.put(attribute, detail);
                result.add(object(type, schema, rows.getString(1), "", attributes));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private Set<String> primaryColumns(DatabaseSession session, String schema, String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<String>();
        for (UniqueKeyInfo key : listUniqueKeys(session, "", schema, table)) {
            if (key.primary()) { columns.addAll(key.columns()); break; }
        }
        return columns;
    }

    private static DatabaseObject object(DatabaseObjectType type, String schema, String name, String remarks,
                                         Map<String, String> attributes) {
        return new DatabaseObject(type, "", schema, name, remarks, attributes);
    }
    private static String requiredSchema(DatabaseNamespace namespace) {
        String schema = namespace.schema().isEmpty() ? namespace.catalog() : namespace.schema();
        if (schema.trim().isEmpty()) throw new IllegalArgumentException("Schema不能为空");
        return upper(schema);
    }
    private void addSchema(Set<String> names, String schema) {
        String normalized = upper(schema);
        if (!normalized.isEmpty() && !isSystemSchema(normalized)) names.add(normalized);
    }
    private static String ddlType(DatabaseObjectType type) {
        switch (type) {
            case TABLE: return "TABLE";
            case VIEW: return "VIEW";
            case INDEX: return "INDEX";
            case TRIGGER: return "TRIGGER";
            case PROCEDURE: return "PROCEDURE";
            case FUNCTION: return "FUNCTION";
            case PACKAGE: return "PACKAGE";
            case SEQUENCE: return "SEQUENCE";
            case SYNONYM: return "SYNONYM";
            case TYPE: return "TYPE";
            default: return "";
        }
    }
    private static String text(Object value) throws SQLException {
        if (value == null) return "";
        if (!(value instanceof Clob)) return value.toString();
        StringBuilder result = new StringBuilder();
        try (Reader reader = ((Clob) value).getCharacterStream()) {
            char[] buffer = new char[4096]; int read;
            while ((read = reader.read(buffer)) >= 0) result.append(buffer, 0, read);
            return result.toString();
        } catch (IOException exception) { throw new SQLException("读取对象定义失败", exception); }
    }
    private static boolean containsIgnoreCase(Set<String> values, String candidate) {
        for (String value : values) if (value.equalsIgnoreCase(candidate)) return true;
        return false;
    }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String value(String value) { return value == null ? "" : value; }
    private static final class KeyBuilder {
        private final String name; private final boolean primary;
        private final TreeMap<Integer, String> columns = new TreeMap<Integer, String>();
        private KeyBuilder(String name, boolean primary) { this.name=name; this.primary=primary; }
    }
}
