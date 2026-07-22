package com.dbstudio.spi;

import java.sql.SQLException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;

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
                            ? listColumns(session, object.catalog(), object.schema(), object.name())
                            : Collections.<ColumnInfo>emptyList();
                    result.add(new CompletionObjectInfo(object, columns));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    List<ColumnInfo> listColumns(
            DatabaseSession session,
            String catalog,
            String schema,
            String objectName) throws SQLException;

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
