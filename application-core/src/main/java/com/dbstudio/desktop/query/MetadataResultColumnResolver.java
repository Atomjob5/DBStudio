package com.dbstudio.desktop.query;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.SqlDialect;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MetadataResultColumnResolver implements ResultColumnResolver {
    private final MetadataAdapter metadata;
    private final DatabaseSession session;
    private final SqlDialect dialect;
    private final Map<String, Map<String, String>> cache = new HashMap<String, Map<String, String>>();

    public MetadataResultColumnResolver(MetadataAdapter metadata, DatabaseSession session, SqlDialect dialect) {
        this.metadata = metadata;
        this.session = session;
        this.dialect = dialect;
    }

    @Override public List<ResultColumn> resolve(String sql, List<ResultColumn> columns) {
        List<String> sourceNames = dialect.resultColumnNames(sql);
        List<ResultColumn> resolved = new ArrayList<ResultColumn>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            ResultColumn column = columns.get(index);
            if (sourceNames.size() == columns.size() && !sourceNames.get(index).isEmpty()) {
                column = column.withName(sourceNames.get(index));
            }
            String remarks = column.remarks();
            if (remarks.isEmpty() && !column.table().isEmpty() && !column.name().isEmpty()) {
                remarks = remarks(column);
            }
            resolved.add(remarks.isEmpty() ? column : column.withRemarks(remarks));
        }
        return Collections.unmodifiableList(resolved);
    }

    private String remarks(ResultColumn column) {
        String key = normalized(column.catalog()) + '\u0000' + normalized(column.schema()) + '\u0000' + normalized(column.table());
        Map<String, String> tableColumns;
        synchronized (session) {
            tableColumns = cache.get(key);
            if (tableColumns == null) {
                tableColumns = load(column);
                cache.put(key, tableColumns);
            }
        }
        String value = tableColumns.get(normalized(column.name()));
        return value == null ? "" : value;
    }

    private Map<String, String> load(ResultColumn source) {
        Map<String, String> result = new HashMap<String, String>();
        try {
            for (ColumnInfo column : metadata.listColumns(session, source.catalog(), source.schema(), source.table())) {
                result.put(normalized(column.name()), column.remarks());
            }
        } catch (SQLException ignored) {
            // Column remarks are optional and must never fail the query result stream.
        }
        return Collections.unmodifiableMap(result);
    }

    @Override public void invalidate() {
        synchronized (session) { cache.clear(); }
    }

    private static String normalized(String value) { return value.toLowerCase(Locale.ROOT); }
}
