package com.dbstudio.desktop.query;

import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.ResultMutationSource;
import com.dbstudio.spi.ResultEditPlan;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.UniqueKeyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public final class MetadataResultColumnResolver implements ResultColumnResolver {
    private final MetadataAdapter metadata;
    private final DatabaseSession session;
    private final SqlDialect dialect;

    public MetadataResultColumnResolver(MetadataAdapter metadata, DatabaseSession session, SqlDialect dialect) {
        this.metadata = metadata;
        this.session = session;
        this.dialect = dialect;
    }

    @Override public PreparedResultQuery prepare(String sql) {
        ResultEditPlan plan = dialect.resultEditPlan(sql);
        if (!plan.editableCandidate() || !plan.source().isPresent()) return new PreparedResultQuery(sql);
        synchronized (session) {
            try {
                ResultMutationSource source = resolveMutationSource(plan.source().get());
                if (ownerUnresolved(source)) return unmodified(sql, source);
                String catalog = source.catalog();
                String schema = source.schema();
                if (!metadata.isBaseTable(session, catalog, schema, source.table())) return unmodified(sql, source);
                String qualifier = dialect.resultMutationQualifier(sql, source);
                List<String> expressions = new ArrayList<String>();
                List<String> aliases = new ArrayList<String>();
                List<String> predicates = new ArrayList<String>();
                List<Integer> jdbcTypes = new ArrayList<Integer>();
                String dialectId = dialect.id().toLowerCase(java.util.Locale.ROOT);
                if (dialectId.contains("oracle")) {
                    expressions.add("ROWIDTOCHAR(" + qualifier + ".ROWID)");
                    aliases.add("DBSTUDIO_LOCATOR_0");
                    predicates.add("ROWID = CHARTOROWID(?)");
                    jdbcTypes.add(java.sql.Types.VARCHAR);
                    return prepared(sql, expressions, aliases,
                            new ResultMutationTarget.Locator("ROWID", predicates, jdbcTypes,
                                    Collections.<String>emptyList()), source);
                }
                List<ColumnInfo> columns = metadata.listColumns(session, catalog, schema, source.table());
                Map<String, ColumnInfo> columnsByName = new HashMap<String, ColumnInfo>();
                for (ColumnInfo column : columns) columnsByName.put(normalized(column.name()), column);
                List<UniqueKeyInfo> keys = new ArrayList<UniqueKeyInfo>(
                        metadata.listUniqueKeys(session, catalog, schema, source.table()));
                Collections.sort(keys, (left, right) -> Boolean.compare(right.primary(), left.primary()));
                UniqueKeyInfo selected = null;
                for (UniqueKeyInfo key : keys) {
                    boolean safe = !key.columns().isEmpty();
                    for (String name : key.columns()) {
                        ColumnInfo column = columnsByName.get(normalized(name));
                        if (column == null || column.nullable()) { safe = false; break; }
                    }
                    if (safe) { selected = key; break; }
                }
                if (selected == null) return unmodified(sql, source);
                for (int index = 0; index < selected.columns().size(); index++) {
                    String name = selected.columns().get(index);
                    ColumnInfo column = columnsByName.get(normalized(name));
                    expressions.add(qualifier + "." + dialect.quoteIdentifier(name));
                    aliases.add("__DBSTUDIO_LOCATOR_" + index);
                    predicates.add(dialect.quoteIdentifier(name) + " = ?");
                    jdbcTypes.add(jdbcType(column == null ? "" : column.typeName()));
                }
                return prepared(sql, expressions, aliases,
                        new ResultMutationTarget.Locator("UNIQUE_KEY", predicates, jdbcTypes,
                                selected.columns()), source);
            } catch (SQLException | RuntimeException ignored) {
                return new PreparedResultQuery(sql);
            }
        }
    }

    private PreparedResultQuery prepared(String sql, List<String> expressions, List<String> aliases,
                                         ResultMutationTarget.Locator locator,
                                         ResultMutationSource source) {
        String rewritten = dialect.appendResultLocatorColumns(sql, expressions, aliases);
        return sql.equals(rewritten) ? unmodified(sql, source)
                : new PreparedResultQuery(sql, rewritten, expressions.size(), locator, source);
    }

    private static PreparedResultQuery unmodified(String sql, ResultMutationSource source) {
        return new PreparedResultQuery(sql, sql, 0, null, source);
    }

    @Override public ResolvedResultMetadata resolve(String sql, List<ResultColumn> columns) {
        return resolve(sql, columns, null);
    }

    private ResolvedResultMetadata resolve(String sql, List<ResultColumn> columns,
                                           ResultMutationSource preparedSource) {
        List<String> sourceNames = dialect.resultColumnNames(sql);
        List<ResultColumn> resolved = new ArrayList<ResultColumn>(columns.size());
        for (int index = 0; index < columns.size(); index++) {
            ResultColumn column = columns.get(index).withQuotedLabel(dialect.quoteIdentifier(columns.get(index).label()));
            if (sourceNames.size() == columns.size() && !sourceNames.get(index).isEmpty()) {
                column = column.withName(sourceNames.get(index));
            }
            resolved.add(column);
        }
        List<ResultColumn> immutable = Collections.unmodifiableList(resolved);
        ResultEditPlan plan = dialect.resultEditPlan(sql);
        ResultMutationSource source = preparedSource;
        if (source == null && plan.source().isPresent()) {
            try {
                synchronized (session) {
                    source = resolveMutationSource(plan.source().get());
                }
            } catch (SQLException | RuntimeException ignored) {
                ResultMutationSource unresolved = plan.source().get();
                return new ResolvedResultMetadata(immutable, readOnlyTarget(
                        qualified(unresolved.catalog(), unresolved.schema(), unresolved.table()), immutable,
                        "METADATA_UNAVAILABLE", "无法读取目标表元数据，结果保持只读", lockMode(sql)));
            }
        }
        return new ResolvedResultMetadata(immutable, mutationTarget(sql, immutable, plan, source));
    }

    @Override public ResolvedResultMetadata resolve(PreparedResultQuery query, List<ResultColumn> columns) {
        ResolvedResultMetadata resolved = resolve(query.originalSql(), columns, query.mutationSource());
        if (query.locator() == null || resolved.mutationTarget() == null) return resolved;
        String reasonCode = resolved.mutationTarget().reasonCode();
        if (!reasonCode.isEmpty() && !"NO_SAFE_ROW_KEY".equals(reasonCode)) return resolved;
        String refreshSql = dialect.appendResultLocatorPredicate(
                query.originalSql(), query.locator().predicates());
        return new ResolvedResultMetadata(resolved.columns(),
                resolved.mutationTarget().withLocator(query.locator(), refreshSql));
    }

    private ResultMutationTarget mutationTarget(String sql, List<ResultColumn> columns,
                                                ResultEditPlan plan, ResultMutationSource resolvedSource) {
        if (columns.isEmpty()) return null;
        if (!plan.source().isPresent()) return readOnlyTarget("", columns,
                plan.reasonCode(), plan.reason(), lockMode(sql));
        ResultMutationSource source = resolvedSource == null ? plan.source().get() : resolvedSource;
        if (ownerUnresolved(source)) return readOnlyTarget(
                qualified(source.catalog(), source.schema(), source.table()), columns,
                "TARGET_OWNER_UNRESOLVED", "无法确定目标表所属 Schema，结果保持只读", lockMode(sql));
        ResultColumn first = columns.get(0);
        String table = source.table();
        String catalog = source.catalog().isEmpty() ? first.catalog() : source.catalog();
        String schema = oracleCompatible() ? source.schema()
                : source.schema().isEmpty() ? first.schema() : source.schema();
        Set<String> names = new HashSet<String>();
        Map<String, Integer> indexByName = new HashMap<String, Integer>();
        List<ResultMutationTarget.Column> targetColumns = new ArrayList<ResultMutationTarget.Column>();
        for (int index = 0; index < columns.size(); index++) {
            ResultColumn column = columns.get(index);
            String name = normalized(column.name());
            if (name.isEmpty()
                    || (!column.table().isEmpty() && !table.equalsIgnoreCase(column.table()))
                    || (!column.catalog().isEmpty() && !catalog.isEmpty()
                        && !catalog.equalsIgnoreCase(column.catalog()))
                    || (!column.schema().isEmpty() && !schema.isEmpty()
                        && !schema.equalsIgnoreCase(column.schema()))
                    || !names.add(name)) return readOnlyTarget(qualified(catalog, schema, table), columns,
                            "AMBIGUOUS_PROJECTION", "结果字段无法唯一映射到目标基表", lockMode(sql));
            indexByName.put(name, index);
        }
        synchronized (session) {
            try {
                if (!metadata.isBaseTable(session, catalog, schema, table)) return readOnlyTarget(
                        qualified(catalog, schema, table), columns, "VIEW_NOT_SUPPORTED",
                        "视图或非基表查询结果暂不支持编辑", lockMode(sql));
                String tableReason = metadata.resultEditTableReason(session, catalog, schema, table);
                if (tableReason != null && !tableReason.trim().isEmpty()) return readOnlyTarget(
                        qualified(catalog, schema, table), columns, "NON_TRANSACTIONAL_TABLE",
                        tableReason, lockMode(sql));
                Map<String, ColumnInfo> tableColumns = new HashMap<String, ColumnInfo>();
                for (ColumnInfo column : metadata.listColumns(session, catalog, schema, table)) {
                    tableColumns.put(normalized(column.name()), column);
                }
                for (int index = 0; index < columns.size(); index++) {
                    ResultColumn column = columns.get(index);
                    ColumnInfo info = tableColumns.get(normalized(column.name()));
                    boolean editable = info != null && !info.generated()
                            && ResultMutationTarget.Column.supportsEditing(column.jdbcType(), column.typeName());
                    targetColumns.add(new ResultMutationTarget.Column(index, column.name(),
                            info == null ? "" : dialect.quoteIdentifier(info.name()), column.jdbcType(), column.typeName(), "",
                            info == null ? 0 : info.size(), info == null ? 0 : info.scale(),
                            info == null || info.nullable(), info == null ? "" : info.defaultValue(),
                            info != null && info.autoIncrement(), info != null && info.generated(), editable,
                            editable ? "" : info != null && info.generated()
                                    ? "生成列或虚拟列不可直接修改" : "该字段类型暂不支持直接编辑"));
                }
                List<ResultMutationTarget.Key> keys = new ArrayList<ResultMutationTarget.Key>();
                for (UniqueKeyInfo key : metadata.listUniqueKeys(session, catalog, schema, table)) {
                    List<Integer> indices = new ArrayList<Integer>();
                    boolean safe = !key.columns().isEmpty();
                    for (String column : key.columns()) {
                        ColumnInfo info = tableColumns.get(normalized(column));
                        if (info == null || info.nullable()) safe = false;
                        Integer index = indexByName.get(normalized(column));
                        if (index == null) { indices.clear(); break; }
                        indices.add(index);
                    }
                    if (safe && !indices.isEmpty()) keys.add(new ResultMutationTarget.Key(
                            key.name(), key.primary(), indices));
                }
                boolean editable = source.editableForUpdate() && !keys.isEmpty();
                String reasonCode = !source.editableForUpdate() ? "FOR_UPDATE_REQUIRED"
                        : keys.isEmpty() ? "NO_SAFE_ROW_KEY" : "";
                String reason = !source.editableForUpdate() ? "需要显式执行单表 FOR UPDATE 查询"
                        : keys.isEmpty() ? "结果必须包含完整的主键或非空唯一键" : "";
                return new ResultMutationTarget(qualified(catalog, schema, table), targetColumns, keys,
                        editable, editable ? "editable" : "readOnly", reasonCode, reason,
                        lockMode(sql), editable, editable, null,
                        dialect.id().toLowerCase(java.util.Locale.ROOT).contains("oracle"));
            } catch (SQLException | RuntimeException ignored) {
                return readOnlyTarget(qualified(catalog, schema, table), columns,
                        "METADATA_UNAVAILABLE", "无法读取目标表元数据，结果保持只读", lockMode(sql));
            }
        }
    }

    private static ResultMutationTarget readOnlyTarget(String qualifiedName, List<ResultColumn> columns,
                                                        String reasonCode, String reason, String lockMode) {
        List<ResultMutationTarget.Column> targetColumns = new ArrayList<ResultMutationTarget.Column>();
        for (int index = 0; index < columns.size(); index++) {
            ResultColumn column = columns.get(index);
            targetColumns.add(new ResultMutationTarget.Column(index, column.name(), "", column.jdbcType(),
                    column.typeName(), "", 0, 0, true, "", false, false, false,
                    reason == null || reason.isEmpty() ? "当前结果只读" : reason));
        }
        return new ResultMutationTarget(qualifiedName, targetColumns,
                Collections.<ResultMutationTarget.Key>emptyList(), false, "readOnly",
                reasonCode, reason, lockMode, false, false);
    }

    private String qualified(String catalog, String schema, String table) {
        return dialect.qualifiedName(catalog, schema, table);
    }

    private ResultMutationSource resolveMutationSource(ResultMutationSource source) throws SQLException {
        String catalog = source.catalog();
        String schema = source.schema();
        if (oracleCompatible()) {
            if (schema.trim().isEmpty()) schema = value(session.currentSchema());
        } else if (catalog.trim().isEmpty()) {
            catalog = value(session.currentCatalog());
        }
        return catalog.equals(source.catalog()) && schema.equals(source.schema()) ? source
                : new ResultMutationSource(catalog, schema, source.table(), source.alias(),
                        source.editableForUpdate());
    }

    private boolean ownerUnresolved(ResultMutationSource source) {
        return oracleCompatible() && source.schema().trim().isEmpty();
    }

    private boolean oracleCompatible() {
        return dialect.id().toLowerCase(java.util.Locale.ROOT).contains("oracle");
    }

    @Override public void invalidate() { }

    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static String normalized(String value) { return value.toLowerCase(java.util.Locale.ROOT); }

    private static String lockMode(String sql) {
        String upper = sql == null ? "" : sql.toUpperCase(java.util.Locale.ROOT);
        if (upper.matches("(?s).*\\bSKIP\\s+LOCKED\\b.*")) return "SKIP_LOCKED";
        if (upper.matches("(?s).*\\bNOWAIT\\b.*")) return "NOWAIT";
        java.util.regex.Matcher wait = java.util.regex.Pattern.compile("\\bWAIT\\s+(\\d+)\\b").matcher(upper);
        return wait.find() ? "WAIT " + wait.group(1) : sourceLock(upper);
    }

    private static String sourceLock(String upper) {
        return upper.matches("(?s).*\\bFOR\\s+UPDATE\\b.*") ? "WAIT" : "NONE";
    }

    private static int jdbcType(String typeName) {
        String upper = typeName == null ? "" : typeName.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("BIGINT")) return java.sql.Types.BIGINT;
        if (upper.contains("INT")) return java.sql.Types.INTEGER;
        if (upper.contains("DECIMAL") || upper.contains("NUMERIC") || upper.contains("NUMBER")) {
            return java.sql.Types.NUMERIC;
        }
        if (upper.contains("TIMESTAMP") || upper.contains("DATETIME")) return java.sql.Types.TIMESTAMP;
        if (upper.startsWith("TIME")) return java.sql.Types.TIME;
        if (upper.contains("DATE")) return java.sql.Types.DATE;
        if (upper.contains("BINARY") || upper.contains("RAW")) return java.sql.Types.VARBINARY;
        return java.sql.Types.VARCHAR;
    }
}
