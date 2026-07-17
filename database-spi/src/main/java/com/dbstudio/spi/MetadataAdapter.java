package com.dbstudio.spi;

import java.sql.SQLException;
import java.util.List;

public interface MetadataAdapter {
    List<String> listCatalogs(DatabaseSession session) throws SQLException;

    List<DatabaseObject> listObjects(
            DatabaseSession session,
            String catalog,
            DatabaseObjectType type) throws SQLException;

    List<ColumnInfo> listColumns(
            DatabaseSession session,
            String catalog,
            String schema,
            String objectName) throws SQLException;

    String definition(DatabaseSession session, DatabaseObject object) throws SQLException;
}
