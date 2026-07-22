package com.dbstudio.mysql;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.UniqueKeyInfo;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class MySqlMetadataAdapter implements MetadataAdapter {
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
                        resultSet.getString("REMARKS")));
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
