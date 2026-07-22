package com.dbstudio.desktop.query;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.ResultMutationSource;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.UniqueKeyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

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

    @Override public ResolvedResultMetadata resolve(String sql, List<ResultColumn> columns) {
        List<String> sourceNames = dialect.resultColumnNames(sql);
        List<ResultColumn> resolved = new ArrayList<ResultColumn>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            ResultColumn column = columns.get(index).withQuotedLabel(dialect.quoteIdentifier(columns.get(index).label()));
            if (sourceNames.size() == columns.size() && !sourceNames.get(index).isEmpty()) {
                column = column.withName(sourceNames.get(index));
            }
            String remarks = column.remarks();
            if (remarks.isEmpty() && !column.table().isEmpty() && !column.name().isEmpty()) {
                remarks = remarks(column);
            }
            resolved.add(remarks.isEmpty() ? column : column.withRemarks(remarks));
        }
        List<ResultColumn> immutable = Collections.unmodifiableList(resolved);
        return new ResolvedResultMetadata(immutable, mutationTarget(sql, immutable));
    }

    private ResultMutationTarget mutationTarget(String sql, List<ResultColumn> columns) {
        Optional<ResultMutationSource> parsed = dialect.resultMutationSource(sql);
        if (!parsed.isPresent() || columns.isEmpty()) return null;
        ResultMutationSource source = parsed.get();
        ResultColumn first = columns.get(0);
        String table = first.table();
        if (table.isEmpty() || !source.table().equalsIgnoreCase(table)) return null;
        String catalog = first.catalog().isEmpty() ? source.catalog() : first.catalog();
        String schema = first.schema().isEmpty() ? source.schema() : first.schema();
        Set<String> names = new HashSet<String>();
        Map<String, Integer> indexByName = new HashMap<String, Integer>();
        List<ResultMutationTarget.Column> targetColumns = new ArrayList<ResultMutationTarget.Column>();
        for (int index = 0; index < columns.size(); index++) {
            ResultColumn column = columns.get(index);
            String name = normalized(column.name());
            if (name.isEmpty() || column.table().isEmpty() || !table.equalsIgnoreCase(column.table())
                    || (!column.catalog().isEmpty() && !catalog.equalsIgnoreCase(column.catalog()))
                    || !names.add(name)) return null;
            indexByName.put(name, index);
            targetColumns.add(new ResultMutationTarget.Column(index, column.name(),
                    dialect.quoteIdentifier(column.name()), column.jdbcType()));
        }
        synchronized (session) {
            try {
                if (!metadata.isBaseTable(session, catalog, schema, table)) return null;
                List<ResultMutationTarget.Key> keys = new ArrayList<ResultMutationTarget.Key>();
                for (UniqueKeyInfo key : metadata.listUniqueKeys(session, catalog, schema, table)) {
                    List<Integer> indices = new ArrayList<Integer>();
                    for (String column : key.columns()) {
                        Integer index = indexByName.get(normalized(column));
                        if (index == null) { indices.clear(); break; }
                        indices.add(index);
                    }
                    if (!indices.isEmpty()) keys.add(new ResultMutationTarget.Key(
                            key.name(), key.primary(), indices));
                }
                return new ResultMutationTarget(qualified(catalog, schema, table), targetColumns, keys);
            } catch (SQLException ignored) {
                return null;
            }
        }
    }

    private String qualified(String catalog, String schema, String table) {
        return dialect.qualifiedName(catalog, schema, table);
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
