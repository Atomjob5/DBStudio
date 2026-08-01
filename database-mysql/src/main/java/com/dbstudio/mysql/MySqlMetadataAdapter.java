package com.dbstudio.mysql;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.CompletionObjectInfo;
import com.dbstudio.spi.DatabaseNamespace;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.UniqueKeyInfo;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** MySQL Catalog、表、字段、备注和唯一键到统一 SPI 模型的映射。 */
public final class MySqlMetadataAdapter implements MetadataAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(MySqlMetadataAdapter.class);
    private static final int COMPLETION_CHUNK_SIZE = 500;
    @Override
    public List<String> listCatalogs(DatabaseSession session) throws SQLException {
        List<String> catalogs = new ArrayList<String>();
        try (ResultSet resultSet = session.jdbcConnection().getMetaData().getCatalogs()) {
            while (resultSet.next()) {
                String catalog = resultSet.getString("TABLE_CAT");
                if (!isSystemCatalog(catalog)) {
                    catalogs.add(catalog);
                }
            }
        }
        catalogs.sort(String.CASE_INSENSITIVE_ORDER);
        return catalogs;
    }

    @Override
    public List<DatabaseNamespace> listCompletionNamespaces(DatabaseSession session) throws SQLException {
        String current = session.currentCatalog();
        List<DatabaseNamespace> result = new ArrayList<DatabaseNamespace>();
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA ORDER BY SCHEMA_NAME");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String name = rows.getString(1);
                result.add(DatabaseNamespace.catalog(name, current != null && current.equalsIgnoreCase(name),
                        isSystemCatalog(name)));
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<CompletionObjectInfo> listCompletionObjects(DatabaseSession session,
                                                               List<DatabaseNamespace> namespaces,
                                                               Set<DatabaseObjectType> types) throws SQLException {
        return listCompletionObjects(session, namespaces, types, MetadataAdapter.CompletionLoadListener.NONE);
    }

    @Override
    public List<CompletionObjectInfo> listCompletionObjects(DatabaseSession session,
                                                               List<DatabaseNamespace> namespaces,
                                                               Set<DatabaseObjectType> types,
                                                               MetadataAdapter.CompletionLoadListener listener) throws SQLException {
        try {
            return batchCompletionObjects(session, namespaces, types);
        } catch (SQLException batchFailure) {
            LOG.warn("MySQL批量读取补全元数据失败，降级为逐对象读取 reason={}", batchFailure.getMessage());
            listener.compatibilityFallback("批量读取失败，正在使用兼容方式加载…");
            return MetadataAdapter.super.listCompletionObjects(session, namespaces, types);
        }
    }

    private List<CompletionObjectInfo> batchCompletionObjects(DatabaseSession session,
                                                                 List<DatabaseNamespace> namespaces,
                                                                 Set<DatabaseObjectType> types) throws SQLException {
        List<String> catalogs = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (DatabaseNamespace namespace : namespaces) {
            String catalog = namespace.catalog();
            if (!catalog.isEmpty() && seen.add(catalog.toLowerCase(Locale.ROOT))) catalogs.add(catalog);
        }
        Map<String, DatabaseObject> objects = new LinkedHashMap<String, DatabaseObject>();
        Map<String, List<ColumnInfo>> columns = new LinkedHashMap<String, List<ColumnInfo>>();
        for (int start = 0; start < catalogs.size(); start += COMPLETION_CHUNK_SIZE) {
            List<String> chunk = catalogs.subList(start, Math.min(catalogs.size(), start + COMPLETION_CHUNK_SIZE));
            String placeholders = placeholders(chunk.size());
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                    "SELECT TABLE_SCHEMA,TABLE_NAME,TABLE_TYPE,TABLE_COMMENT FROM information_schema.TABLES "
                            + "WHERE TABLE_SCHEMA IN (" + placeholders + ") AND TABLE_TYPE IN ('BASE TABLE','VIEW','SYSTEM VIEW') "
                            + "ORDER BY TABLE_SCHEMA,TABLE_NAME")) {
                bind(statement, chunk);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        DatabaseObjectType type = value(rows.getString(3)).toUpperCase(Locale.ROOT).contains("VIEW")
                                ? DatabaseObjectType.VIEW : DatabaseObjectType.TABLE;
                        if (!types.contains(type)) continue;
                        String catalog = rows.getString(1); String name = rows.getString(2);
                        objects.put(completionKey(catalog, name), new DatabaseObject(type, catalog, "", name,
                                value(rows.getString(4)), Collections.<String, String>emptyMap()));
                    }
                }
            }
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                    "SELECT TABLE_SCHEMA,TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,COLUMN_COMMENT,ORDINAL_POSITION "
                            + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN (" + placeholders + ") "
                            + "ORDER BY TABLE_SCHEMA,TABLE_NAME,ORDINAL_POSITION")) {
                bind(statement, chunk);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String key = completionKey(rows.getString(1), rows.getString(2));
                        if (!objects.containsKey(key)) continue;
                        List<ColumnInfo> values = columns.get(key);
                        if (values == null) { values = new ArrayList<ColumnInfo>(); columns.put(key, values); }
                        values.add(new ColumnInfo(rows.getString(3), rows.getString(4), 0, 0, true, "", false,
                                rows.getInt(6), value(rows.getString(5))));
                    }
                }
            }
        }
        List<CompletionObjectInfo> result = new ArrayList<CompletionObjectInfo>(objects.size());
        for (Map.Entry<String, DatabaseObject> entry : objects.entrySet()) {
            List<ColumnInfo> values = columns.get(entry.getKey());
            result.add(new CompletionObjectInfo(entry.getValue(), values == null
                    ? Collections.<ColumnInfo>emptyList() : values));
        }
        return Collections.unmodifiableList(result);
    }

    private static String placeholders(int size) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < size; index++) { if (index > 0) result.append(','); result.append('?'); }
        return result.toString();
    }

    private static void bind(PreparedStatement statement, List<String> values) throws SQLException {
        for (int index = 0; index < values.size(); index++) statement.setString(index + 1, values.get(index));
    }

    private static String completionKey(String catalog, String objectName) {
        return value(catalog).toUpperCase(Locale.ROOT) + '\u0000' + value(objectName).toUpperCase(Locale.ROOT);
    }

    private static String value(String value) { return value == null ? "" : value; }

    @Override
    public List<DatabaseObject> listObjects(
            DatabaseSession session,
            String catalog,
            DatabaseObjectType type) throws SQLException {
        switch (type) {
            case TABLE: return tables(session, catalog, new String[]{"TABLE"}, DatabaseObjectType.TABLE);
            case VIEW: return tables(session, catalog, new String[]{"VIEW"}, DatabaseObjectType.VIEW);
            case INDEX: return indexes(session, catalog);
            case CONSTRAINT: return queryNamedObjects(session, catalog, DatabaseObjectType.CONSTRAINT,
                    "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                            + "WHERE CONSTRAINT_SCHEMA=? ORDER BY TABLE_NAME, CONSTRAINT_NAME");
            case TRIGGER: return queryNamedObjects(session, catalog, DatabaseObjectType.TRIGGER,
                    "SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=? ORDER BY TRIGGER_NAME");
            case PROCEDURE: return routines(session, catalog, "PROCEDURE", DatabaseObjectType.PROCEDURE);
            case FUNCTION: return routines(session, catalog, "FUNCTION", DatabaseObjectType.FUNCTION);
            default: return Collections.emptyList();
        }
    }

    @Override
    public List<ColumnInfo> listColumns(
            DatabaseSession session,
            String catalog,
            String schema,
            String objectName) throws SQLException {
        DatabaseMetaData metadata = session.jdbcConnection().getMetaData();
        Set<String> primaryKeys = primaryKeys(metadata, catalog, objectName);
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        try (ResultSet resultSet = metadata.getColumns(catalog, schemaOrNull(schema), objectName, "%")) {
            while (resultSet.next()) {
                String name = resultSet.getString("COLUMN_NAME");
                columns.add(new ColumnInfo(
                        name,
                        resultSet.getString("TYPE_NAME"),
                        resultSet.getInt("COLUMN_SIZE"),
                        resultSet.getInt("DECIMAL_DIGITS"),
                        resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        resultSet.getString("COLUMN_DEF"),
                        primaryKeys.contains(name),
                        resultSet.getInt("ORDINAL_POSITION"),
                        resultSet.getString("REMARKS"),
                        yes(resultSet, "IS_AUTOINCREMENT"),
                        yes(resultSet, "IS_GENERATEDCOLUMN")));
            }
        }
        Collections.sort(columns, new Comparator<ColumnInfo>() {
            @Override public int compare(ColumnInfo left, ColumnInfo right) {
                return Integer.compare(left.ordinal(), right.ordinal());
            }
        });
        return columns;
    }

    private static boolean yes(ResultSet resultSet, String column) {
        try { return "YES".equalsIgnoreCase(resultSet.getString(column)); }
        catch (SQLException ignored) { return false; }
    }

    @Override
    public List<ColumnInfo> listCompletionColumns(DatabaseSession session, String catalog, String schema,
                                                   String objectName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        try (ResultSet resultSet = session.jdbcConnection().getMetaData()
                .getColumns(catalog, schemaOrNull(schema), objectName, "%")) {
            while (resultSet.next()) {
                columns.add(new ColumnInfo(resultSet.getString("COLUMN_NAME"), resultSet.getString("TYPE_NAME"),
                        resultSet.getInt("COLUMN_SIZE"), resultSet.getInt("DECIMAL_DIGITS"),
                        resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, "", false,
                        resultSet.getInt("ORDINAL_POSITION"), value(resultSet.getString("REMARKS"))));
            }
        }
        Collections.sort(columns, new Comparator<ColumnInfo>() {
            @Override public int compare(ColumnInfo left, ColumnInfo right) {
                return Integer.compare(left.ordinal(), right.ordinal());
            }
        });
        return columns;
    }

    @Override
    public boolean isBaseTable(DatabaseSession session, String catalog, String schema,
                               String objectName) throws SQLException {
        try (ResultSet resultSet = session.jdbcConnection().getMetaData()
                .getTables(catalog, schemaOrNull(schema), objectName, new String[]{"TABLE"})) {
            while (resultSet.next()) {
                if (objectName.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }

    @Override public String resultEditTableReason(DatabaseSession session, String catalog, String schema,
                                                   String objectName) throws SQLException {
        String effectiveCatalog = catalog == null || catalog.isEmpty() ? session.currentCatalog() : catalog;
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")) {
            statement.setString(1, effectiveCatalog);
            statement.setString(2, objectName);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return "无法确认目标表存储引擎";
                String engine = rows.getString(1);
                return "INNODB".equalsIgnoreCase(engine) ? ""
                        : "MySQL 结果编辑仅支持 InnoDB 事务表（当前引擎：" + value(engine) + "）";
            }
        }
    }

    @Override
    public List<UniqueKeyInfo> listUniqueKeys(DatabaseSession session, String catalog, String schema,
                                               String objectName) throws SQLException {
        DatabaseMetaData metadata = session.jdbcConnection().getMetaData();
        List<UniqueKeyInfo> keys = new ArrayList<UniqueKeyInfo>();
        TreeMap<Integer, String> primary = new TreeMap<Integer, String>();
        String primaryName = "PRIMARY";
        try (ResultSet resultSet = metadata.getPrimaryKeys(catalog, schemaOrNull(schema), objectName)) {
            while (resultSet.next()) {
                primary.put(resultSet.getInt("KEY_SEQ"), resultSet.getString("COLUMN_NAME"));
                String name = resultSet.getString("PK_NAME");
                if (name != null && !name.trim().isEmpty()) primaryName = name;
            }
        }
        if (!primary.isEmpty()) keys.add(new UniqueKeyInfo(primaryName, true,
                new ArrayList<String>(primary.values())));

        Map<String, TreeMap<Integer, String>> indexes = new LinkedHashMap<String, TreeMap<Integer, String>>();
        try (ResultSet resultSet = metadata.getIndexInfo(catalog, schemaOrNull(schema), objectName, true, false)) {
            while (resultSet.next()) {
                if (resultSet.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic
                        || resultSet.getBoolean("NON_UNIQUE")) continue;
                String name = resultSet.getString("INDEX_NAME");
                String column = resultSet.getString("COLUMN_NAME");
                if (name == null || column == null || "PRIMARY".equalsIgnoreCase(name)) continue;
                TreeMap<Integer, String> index = indexes.get(name);
                if (index == null) {
                    index = new TreeMap<Integer, String>();
                    indexes.put(name, index);
                }
                index.put(resultSet.getInt("ORDINAL_POSITION"), column);
            }
        }
        for (Map.Entry<String, TreeMap<Integer, String>> entry : indexes.entrySet()) {
            if (!entry.getValue().isEmpty()) keys.add(new UniqueKeyInfo(entry.getKey(), false,
                    new ArrayList<String>(entry.getValue().values())));
        }
        return Collections.unmodifiableList(keys);
    }

    @Override
    public String definition(DatabaseSession session, DatabaseObject object) throws SQLException {
        String prefix;
        switch (object.type()) {
            case TABLE: prefix = "SHOW CREATE TABLE "; break;
            case VIEW: prefix = "SHOW CREATE VIEW "; break;
            case TRIGGER: prefix = "SHOW CREATE TRIGGER "; break;
            case PROCEDURE: prefix = "SHOW CREATE PROCEDURE "; break;
            case FUNCTION: prefix = "SHOW CREATE FUNCTION "; break;
            default: throw new SQLException("不支持查看 " + object.type().displayName() + " 的定义");
        }
        String sql = prefix + qualified(object.catalog(), object.name());
        try (java.sql.Statement statement = session.jdbcConnection().createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return "";
            }
            return resultSet.getString(resultSet.getMetaData().getColumnCount());
        }
    }

    private List<DatabaseObject> tables(
            DatabaseSession session,
            String catalog,
            String[] tableTypes,
            DatabaseObjectType targetType) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<DatabaseObject>();
        try (ResultSet resultSet = session.jdbcConnection().getMetaData()
                .getTables(catalog, null, "%", tableTypes)) {
            while (resultSet.next()) {
                objects.add(new DatabaseObject(
                        targetType,
                        catalog,
                        "",
                        resultSet.getString("TABLE_NAME"),
                        resultSet.getString("REMARKS"),
                        Collections.<String, String>emptyMap()));
            }
        }
        objects.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        return objects;
    }

    private List<DatabaseObject> indexes(DatabaseSession session, String catalog) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<DatabaseObject>();
        try (java.sql.PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT DISTINCT INDEX_NAME, TABLE_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME, INDEX_NAME")) {
            statement.setString(1, catalog);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    objects.add(new DatabaseObject(
                            DatabaseObjectType.INDEX,
                            catalog,
                            "",
                            resultSet.getString("INDEX_NAME"),
                            "",
                            Collections.singletonMap("table", resultSet.getString("TABLE_NAME"))));
                }
            }
        }
        return objects;
    }

    private List<DatabaseObject> routines(
            DatabaseSession session,
            String catalog,
            String routineType,
            DatabaseObjectType targetType) throws SQLException {
        try (java.sql.PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT ROUTINE_NAME FROM information_schema.ROUTINES "
                        + "WHERE ROUTINE_SCHEMA=? AND ROUTINE_TYPE=? ORDER BY ROUTINE_NAME")) {
            statement.setString(1, catalog);
            statement.setString(2, routineType);
            return readNamedObjects(statement.executeQuery(), catalog, targetType);
        }
    }

    private List<DatabaseObject> queryNamedObjects(
            DatabaseSession session,
            String catalog,
            DatabaseObjectType targetType,
            String sql) throws SQLException {
        try (java.sql.PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
            statement.setString(1, catalog);
            return readNamedObjects(statement.executeQuery(), catalog, targetType);
        }
    }

    private List<DatabaseObject> readNamedObjects(
            ResultSet resultSet,
            String catalog,
            DatabaseObjectType type) throws SQLException {
        try {
            List<DatabaseObject> objects = new ArrayList<DatabaseObject>();
            while (resultSet.next()) {
                objects.add(new DatabaseObject(type, catalog, "", resultSet.getString(1), "",
                        Collections.<String, String>emptyMap()));
            }
            return objects;
        } finally {
            resultSet.close();
        }
    }

    private Set<String> primaryKeys(DatabaseMetaData metadata, String catalog, String table) throws SQLException {
        Set<String> keys = new HashSet<String>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(catalog, null, table)) {
            while (resultSet.next()) {
                keys.add(resultSet.getString("COLUMN_NAME"));
            }
        }
        return keys;
    }

    private static boolean isSystemCatalog(String catalog) {
        return catalog == null || Arrays.asList("information_schema", "mysql", "performance_schema", "sys")
                .contains(catalog.toLowerCase());
    }

    private static String schemaOrNull(String schema) {
        return schema == null || schema.trim().isEmpty() ? null : schema;
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String qualified(String catalog, String name) {
        return catalog == null || catalog.trim().isEmpty() ? quote(name) : quote(catalog) + "." + quote(name);
    }
}
