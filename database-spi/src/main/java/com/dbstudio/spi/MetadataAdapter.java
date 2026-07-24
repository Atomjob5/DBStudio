package com.dbstudio.spi;

import java.sql.SQLException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;

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

    default List<UniqueKeyInfo> listUniqueKeys(DatabaseSession session, String catalog, String schema,
                                                String objectName) throws SQLException {
        return Collections.emptyList();
    }

    String definition(DatabaseSession session, DatabaseObject object) throws SQLException;
}
