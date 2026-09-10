package com.dbstudio.spi;

import java.sql.SQLException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.io.IOException;
import java.io.Writer;

/**
 * 数据库元数据适配器。
 *
 * <p>负责把不同数据库的数据字典映射为统一的命名空间、对象、字段和约束模型。默认方法提供
 * 兼容性降级实现，具体 Provider 可用批量字典查询覆盖以提升补全性能。</p>
 */
public interface MetadataAdapter {
    default List<String> listCatalogs(DatabaseSession session) throws SQLException {
        return Collections.emptyList();
    }

    default List<DatabaseNamespace> listNamespaces(DatabaseSession session) throws SQLException {
        String current = session.currentCatalog();
        java.util.ArrayList<DatabaseNamespace> result = new java.util.ArrayList<DatabaseNamespace>();
        for (String catalog : listCatalogs(session)) {
            result.add(DatabaseNamespace.catalog(catalog,
                    current != null && current.equalsIgnoreCase(catalog)));
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns every namespace that may be explicitly selected for SQL completion, including system namespaces. */
    default List<DatabaseNamespace> listCompletionNamespaces(DatabaseSession session) throws SQLException {
        return listNamespaces(session);
    }

    default List<DatabaseObject> listObjects(
            DatabaseSession session,
            String catalog,
            DatabaseObjectType type) throws SQLException {
        return Collections.emptyList();
    }

    default List<DatabaseObject> listObjects(DatabaseSession session,
                                              DatabaseNamespace namespace,
                                              DatabaseObjectType type) throws SQLException {
        return listObjects(session, namespace.catalog().isEmpty() ? namespace.schema() : namespace.catalog(), type);
    }

    /**
     * Returns metadata for a complete editor-completion snapshot. Providers backed by data dictionaries may
     * override this method to batch object and column discovery across all namespaces.
     */
    default List<CompletionObjectInfo> listCompletionObjects(DatabaseSession session,
                                                               List<DatabaseNamespace> namespaces,
                                                               Set<DatabaseObjectType> types) throws SQLException {
        List<CompletionObjectInfo> result = new ArrayList<CompletionObjectInfo>();
        for (DatabaseNamespace namespace : namespaces) {
            for (DatabaseObjectType type : types) {
                for (DatabaseObject object : listObjects(session, namespace, type)) {
                    List<ColumnInfo> columns = type == DatabaseObjectType.TABLE || type == DatabaseObjectType.VIEW
                            ? listCompletionColumns(session, object.catalog(), object.schema(), object.name())
                            : Collections.<ColumnInfo>emptyList();
                    result.add(new CompletionObjectInfo(object, columns));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    default List<CompletionObjectInfo> listCompletionObjects(DatabaseSession session,
                                                               List<DatabaseNamespace> namespaces,
                                                               Set<DatabaseObjectType> types,
                                                               CompletionLoadListener listener) throws SQLException {
        return listCompletionObjects(session, namespaces, types);
    }

    /** Returns visible synonyms relevant to the selected completion namespaces. */
    default List<CompletionSynonymInfo> listCompletionSynonyms(DatabaseSession session,
                                                                 List<DatabaseNamespace> namespaces)
            throws SQLException {
        return Collections.emptyList();
    }

    interface CompletionLoadListener {
        CompletionLoadListener NONE = new CompletionLoadListener() {
            @Override public void compatibilityFallback(String message) { }
        };
        void compatibilityFallback(String message);
    }

    /** Whether this provider can stream a comments-first completion snapshot. */
    default boolean supportsStreamingCompletionMetadata() {
        return false;
    }

    /**
     * Streams objects and column comments for a completion cache without materializing one
     * provider-side snapshot. Implementations must keep every query scoped to {@code namespaces}.
     */
    default void streamCompletionMetadata(DatabaseSession session,
                                            List<DatabaseNamespace> namespaces,
                                            Set<DatabaseObjectType> types,
                                            CompletionMetadataListener listener) throws SQLException {
        throw new SQLException("当前数据库类型不支持流式补全元数据");
    }

    List<ColumnInfo> listColumns(
            DatabaseSession session,
            String catalog,
            String schema,
            String objectName) throws SQLException;

    /** Completion-only fallback that may omit keys, defaults and other expensive structural details. */
    default List<ColumnInfo> listCompletionColumns(DatabaseSession session, String catalog, String schema,
                                                    String objectName) throws SQLException {
        return listColumns(session, catalog, schema, objectName);
    }

    default boolean isBaseTable(DatabaseSession session, String catalog, String schema,
                                String objectName) throws SQLException {
        return false;
    }

    /** Empty when row editing is safe for the physical table; otherwise a user-facing read-only reason. */
    default String resultEditTableReason(DatabaseSession session, String catalog, String schema,
                                         String objectName) throws SQLException {
        return "";
    }

    default List<UniqueKeyInfo> listUniqueKeys(DatabaseSession session, String catalog, String schema,
                                                String objectName) throws SQLException {
        return Collections.emptyList();
    }

    /** Resolves exactly one table/view and must not enumerate an entire database. */
    default DatabaseObject findObject(DatabaseSession session, String catalog, String schema,
                                      String objectName) throws SQLException {
        String[] types = new String[] { "TABLE", "VIEW" };
        try (java.sql.ResultSet rows = session.jdbcConnection().getMetaData().getTables(
                emptyToNull(catalog), emptyToNull(schema), objectName, types)) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME");
                if (!objectName.equalsIgnoreCase(name)) continue;
                String rawType = rows.getString("TABLE_TYPE");
                DatabaseObjectType type = rawType != null && rawType.toUpperCase(java.util.Locale.ROOT).contains("VIEW")
                        ? DatabaseObjectType.VIEW : DatabaseObjectType.TABLE;
                return new DatabaseObject(type, value(rows.getString("TABLE_CAT")), value(rows.getString("TABLE_SCHEM")),
                        name, value(rows.getString("REMARKS")), Collections.<String, String>emptyMap());
            }
        }
        return null;
    }

    default List<ObjectIndexInfo> listIndexes(DatabaseSession session, String catalog, String schema,
                                               String objectName) throws SQLException {
        return Collections.emptyList();
    }

    default MetadataPage<ObjectPartitionInfo> listPartitions(DatabaseSession session, String catalog, String schema,
                                                               String objectName, String pageToken,
                                                               int pageSize) throws SQLException {
        return MetadataPage.empty();
    }

    default MetadataPage<ObjectPartitionInfo> listSubpartitions(DatabaseSession session, String catalog,
                                                                  String schema, String objectName,
                                                                  String parentPartition, String pageToken,
                                                                  int pageSize) throws SQLException {
        return MetadataPage.empty();
    }

    /** Writes the complete rebuild DDL without passing through query result/LOB preview conversion. */
    default void writeRebuildDdl(DatabaseSession session, DatabaseObject object, Writer writer)
            throws SQLException, IOException {
        writer.write(definition(session, object));
    }

    static String emptyToNull(String value) { return value == null || value.isEmpty() ? null : value; }
    static String value(String value) { return value == null ? "" : value; }

    String definition(DatabaseSession session, DatabaseObject object) throws SQLException;
}
