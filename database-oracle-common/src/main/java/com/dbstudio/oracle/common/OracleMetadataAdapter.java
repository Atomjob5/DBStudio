package com.dbstudio.oracle.common;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.CompletionColumnComments;
import com.dbstudio.spi.CompletionMetadataListener;
import com.dbstudio.spi.CompletionObjectInfo;
import com.dbstudio.spi.CompletionSynonymInfo;
import com.dbstudio.spi.DatabaseNamespace;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.MetadataPage;
import com.dbstudio.spi.ObjectIndexColumnInfo;
import com.dbstudio.spi.ObjectIndexInfo;
import com.dbstudio.spi.ObjectPartitionInfo;
import com.dbstudio.spi.UniqueKeyInfo;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Metadata implementation shared by native Oracle and OceanBase Oracle mode. */
/**
 * Oracle 数据字典适配器。
 *
 * <p>优先使用 ALL_* 视图获取可访问 Schema；权限不足时回退到 JDBC DatabaseMetaData，确保对象树和
 * 补全仍能提供可理解的部分结果。</p>
 */
public class OracleMetadataAdapter implements MetadataAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(OracleMetadataAdapter.class);
    private static final int COMPLETION_CHUNK_SIZE = 500;
    private static final int COMPLETION_OBJECT_BATCH_SIZE = 1000;
    private static final int COMPLETION_COLUMN_BATCH_SIZE = 2000;
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

    @Override public List<DatabaseNamespace> listCompletionNamespaces(DatabaseSession session) throws SQLException {
        String current = upper(session.currentSchema());
        Set<String> names = new LinkedHashSet<String>();
        try (Statement statement = session.jdbcConnection().createStatement();
             ResultSet result = statement.executeQuery("SELECT DISTINCT OWNER FROM ALL_TAB_COMMENTS ORDER BY OWNER")) {
            while (result.next()) {
                String name = upper(result.getString(1));
                if (!name.isEmpty()) names.add(name);
            }
        } catch (SQLException dictionaryFailure) {
            try (ResultSet result = session.jdbcConnection().getMetaData().getSchemas()) {
                while (result.next()) {
                    String name = upper(result.getString("TABLE_SCHEM"));
                    if (!name.isEmpty()) names.add(name);
                }
            }
        }
        if (!current.isEmpty()) names.add(current);
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted, String.CASE_INSENSITIVE_ORDER);
        if (sorted.remove(current)) sorted.add(0, current);
        List<DatabaseNamespace> result = new ArrayList<DatabaseNamespace>(sorted.size());
        for (String name : sorted) result.add(DatabaseNamespace.schema(name,
                name.equalsIgnoreCase(current), isSystemSchema(name)));
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
        return listCompletionObjects(session, namespaces, types, MetadataAdapter.CompletionLoadListener.NONE);
    }

    @Override public List<CompletionObjectInfo> listCompletionObjects(DatabaseSession session,
                                                                       List<DatabaseNamespace> namespaces,
                                                                       Set<DatabaseObjectType> types,
                                                                       MetadataAdapter.CompletionLoadListener listener)
            throws SQLException {
        final Map<String, DatabaseObject> objects = new LinkedHashMap<String, DatabaseObject>();
        final Map<String, List<ColumnInfo>> columns = new LinkedHashMap<String, List<ColumnInfo>>();
        streamCompletionMetadata(session, namespaces, types, new CompletionMetadataListener() {
            @Override public void objects(List<DatabaseObject> values) {
                for (DatabaseObject value : values) objects.put(objectKey(value.schema(), value.name()), value);
            }
            @Override public void supplementalTables(List<DatabaseObject> values) {
                for (DatabaseObject value : values) {
                    String key = objectKey(value.schema(), value.name());
                    if (!objects.containsKey(key)) objects.put(key, value);
                }
            }
            @Override public void columns(List<CompletionColumnComments> groups) {
                for (CompletionColumnComments group : groups) {
                    String key = objectKey(group.schema(), group.objectName());
                    List<ColumnInfo> existing = columns.get(key);
                    if (existing == null) {
                        existing = new ArrayList<ColumnInfo>();
                        columns.put(key, existing);
                    }
                    existing.addAll(group.columns());
                }
            }
            @Override public void warning(String phase, String message) {
                LOG.warn("Oracle兼容数据库补全元数据补充阶段失败 phase={} message={}", phase, message);
                listener.compatibilityFallback("completion-metadata:" + phase + ":" + message);
            }
        });
        List<CompletionObjectInfo> result = new ArrayList<CompletionObjectInfo>(objects.size());
        for (Map.Entry<String, DatabaseObject> entry : objects.entrySet()) {
            List<ColumnInfo> values = columns.get(entry.getKey());
            result.add(new CompletionObjectInfo(entry.getValue(), values == null
                    ? Collections.<ColumnInfo>emptyList() : values));
        }
        return Collections.unmodifiableList(result);
    }

    @Override public boolean supportsStreamingCompletionMetadata() {
        return true;
    }

    @Override public List<CompletionSynonymInfo> listCompletionSynonyms(DatabaseSession session,
                                                                          List<DatabaseNamespace> namespaces)
            throws SQLException {
        List<String> owners = completionSchemas(namespaces);
        if (owners.isEmpty()) return Collections.emptyList();
        Map<String, CompletionSynonymInfo> result = new LinkedHashMap<String, CompletionSynonymInfo>();
        for (int start = 0; start < owners.size(); start += COMPLETION_CHUNK_SIZE) {
            List<String> chunk = new ArrayList<String>(owners.subList(start,
                    Math.min(owners.size(), start + COMPLETION_CHUNK_SIZE)));
            // PUBLIC is intentionally queried together with selected private owners. A
            // public synonym may point at SYS or another unselected owner; the client
            // will keep it as "unconfirmed" unless the target is loaded.
            if (!chunk.contains("PUBLIC")) chunk.add("PUBLIC");
            String sql = "SELECT OWNER,SYNONYM_NAME,TABLE_OWNER,TABLE_NAME,DB_LINK FROM ALL_SYNONYMS WHERE OWNER IN ("
                    + placeholders(chunk.size()) + ") ORDER BY OWNER,SYNONYM_NAME";
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
                bindSchemas(statement, chunk);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String owner = upper(rows.getString(1));
                        String name = value(rows.getString(2));
                        String targetOwner = upper(rows.getString(3));
                        String targetName = value(rows.getString(4));
                        if (name.isEmpty() || targetName.isEmpty()) continue;
                        CompletionSynonymInfo synonym = new CompletionSynonymInfo(owner, name, targetOwner,
                                targetName, value(rows.getString(5)), "PUBLIC".equals(owner));
                        result.put(owner + "\u0000" + name, synonym);
                    }
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<CompletionSynonymInfo>(result.values()));
    }

    @Override public void streamCompletionMetadata(DatabaseSession session,
                                                     List<DatabaseNamespace> namespaces,
                                                     Set<DatabaseObjectType> types,
                                                     CompletionMetadataListener listener) throws SQLException {
        List<String> schemas = completionSchemas(namespaces);
        Set<String> knownObjects = new LinkedHashSet<String>();
        try {
            streamCommentObjects(session, schemas, types, knownObjects, listener);
        } catch (SQLException objectFailure) {
            LOG.warn("Oracle兼容数据库对象备注目录不可用 reason={}", objectFailure.getMessage());
            listener.warning("objects", "对象目录不可用，未知对象诊断已跳过：" + value(objectFailure.getMessage()));
        } catch (RuntimeException compatibilityFailure) {
            LOG.debug("Oracle兼容数据库对象备注目录不可用", compatibilityFailure);
            listener.warning("objects", "对象目录不可用，未知对象诊断已跳过：" + value(compatibilityFailure.getMessage()));
        }
        try {
            streamSupplementalTables(session, schemas, types, knownObjects, listener);
        } catch (SQLException supplementalFailure) {
            LOG.warn("Oracle兼容数据库ALL_TABLES补充失败 reason={}", supplementalFailure.getMessage());
            listener.warning("tables", "ALL_TABLES补充表信息失败：" + value(supplementalFailure.getMessage()));
        } catch (RuntimeException compatibilityFailure) {
            LOG.debug("Oracle兼容数据库ALL_TABLES补充不可用", compatibilityFailure);
            listener.warning("tables", "ALL_TABLES补充表信息失败：" + value(compatibilityFailure.getMessage()));
        }
        try {
            streamColumnComments(session, schemas, knownObjects, listener);
        } catch (SQLException columnFailure) {
            LOG.warn("Oracle兼容数据库字段备注补充失败 reason={}", columnFailure.getMessage());
            listener.warning("columns", "字段目录不可用，字段诊断已跳过：" + value(columnFailure.getMessage()));
        } catch (RuntimeException compatibilityFailure) {
            LOG.debug("Oracle兼容数据库字段备注目录不可用", compatibilityFailure);
            listener.warning("columns", "字段目录不可用，字段诊断已跳过：" + value(compatibilityFailure.getMessage()));
        }
        List<CompletionSynonymInfo> synonyms;
        try {
            synonyms = listCompletionSynonyms(session, namespacesFromSchemas(schemas));
        } catch (SQLException optionalFailure) {
            // Synonyms improve semantic confidence but are not required for completion.
            // Report the reduced confidence without aborting the object/column stream.
            listener.warning("synonyms", "同义词目录不可用，相关对象诊断已跳过：" + value(optionalFailure.getMessage()));
            synonyms = Collections.emptyList();
        } catch (RuntimeException compatibilityFailure) {
            // A compatibility driver may reject ALL_SYNONYMS with an unchecked
            // exception. This optional dictionary must never abort completion.
            LOG.debug("Oracle兼容数据库同义词目录不可用", compatibilityFailure);
            listener.warning("synonyms", "同义词目录不可用，相关对象诊断已跳过：" + value(compatibilityFailure.getMessage()));
            synonyms = Collections.emptyList();
        }
        if (!synonyms.isEmpty()) listener.synonyms(synonyms);
    }

    @Override public List<ColumnInfo> listColumns(DatabaseSession session, String catalog, String schema,
                                                   String objectName) throws SQLException {
        String owner = schema == null || schema.trim().isEmpty() ? catalog : schema;
        Set<String> primary = primaryColumns(session, owner, objectName);
        List<ColumnInfo> columns = jdbcColumns(session, owner, objectName, primary);
        String normalizedOwner = upper(owner);
        String normalizedTable = upper(objectName);
        String resolvedOwner = owner;
        String resolvedTable = objectName;
        if (columns.isEmpty() && (!normalizedOwner.equals(owner) || !normalizedTable.equals(objectName))) {
            columns = jdbcColumns(session, normalizedOwner, normalizedTable, primary);
            resolvedOwner = normalizedOwner;
            resolvedTable = normalizedTable;
        }
        columns = supplementColumnComments(session, resolvedOwner, resolvedTable, columns);
        Collections.sort(columns, new Comparator<ColumnInfo>() {
            @Override public int compare(ColumnInfo left, ColumnInfo right) {
                return Integer.compare(left.ordinal(), right.ordinal());
            }
        });
        return Collections.unmodifiableList(columns);
    }

    /**
     * Oracle's JDBC metadata commonly leaves COLUMN_REMARKS empty even when ALL_COL_COMMENTS contains a
     * comment.  Read the dictionary for this one object and merge only useful values so a missing dictionary
     * privilege never prevents the structure window from opening.
     */
    private List<ColumnInfo> supplementColumnComments(DatabaseSession session, String owner, String objectName,
                                                       List<ColumnInfo> columns) {
        if (columns.isEmpty() || owner == null || owner.trim().isEmpty()
                || objectName == null || objectName.trim().isEmpty()) return columns;
        try {
            String objectNameColumn = completionCommentObjectNameColumn(session);
            Map<String, String> comments = columnComments(session, objectNameColumn, owner, objectName);
            String normalizedOwner = upper(owner);
            String normalizedObject = upper(objectName);
            if (comments.isEmpty() && (!normalizedOwner.equals(owner) || !normalizedObject.equals(objectName))) {
                comments = columnComments(session, objectNameColumn, normalizedOwner, normalizedObject);
            }
            if (comments.isEmpty()) return columns;
            List<ColumnInfo> enriched = new ArrayList<ColumnInfo>(columns.size());
            for (ColumnInfo column : columns) {
                String comment = comments.get(column.name());
                if (comment == null) {
                    for (Map.Entry<String, String> entry : comments.entrySet()) {
                        if (entry.getKey().equalsIgnoreCase(column.name())) {
                            comment = entry.getValue();
                            break;
                        }
                    }
                }
                if (comment == null || comment.trim().isEmpty()) {
                    enriched.add(column);
                } else {
                    enriched.add(new ColumnInfo(column.name(), column.typeName(), column.size(), column.scale(),
                            column.nullable(), column.defaultValue(), column.primaryKey(), column.ordinal(), comment,
                            column.autoIncrement(), column.generated()));
                }
            }
            return enriched;
        } catch (SQLException | RuntimeException failure) {
            LOG.debug("Oracle兼容数据库字段备注读取失败 owner={} object={} reason={}", owner, objectName,
                    failure.getMessage());
            return columns;
        }
    }

    private Map<String, String> columnComments(DatabaseSession session, String objectNameColumn,
                                                String owner, String objectName) throws SQLException {
        Map<String, String> comments = new LinkedHashMap<String, String>();
        String sql = "SELECT COLUMN_NAME,COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER=? AND "
                + objectNameColumn + "=? ORDER BY COLUMN_NAME";
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setString(2, objectName);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) comments.put(rows.getString(1), value(rows.getString(2)));
            }
        }
        return comments;
    }

    @Override public DatabaseObject findObject(DatabaseSession session, String catalog, String schema,
                                                String objectName) throws SQLException {
        String owner = upper(schema == null || schema.trim().isEmpty()
                ? (catalog == null || catalog.trim().isEmpty() ? session.currentSchema() : catalog) : schema);
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT o.OWNER,o.OBJECT_NAME,o.OBJECT_TYPE,c.COMMENTS FROM ALL_OBJECTS o "
                        + "LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER=o.OWNER AND c.TABLE_NAME=o.OBJECT_NAME "
                        + "WHERE o.OWNER=? AND o.OBJECT_NAME=? AND o.OBJECT_TYPE IN ('TABLE','VIEW')")) {
            statement.setString(1, owner); statement.setString(2, upper(objectName));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                DatabaseObjectType type = "VIEW".equals(rows.getString(3))
                        ? DatabaseObjectType.VIEW : DatabaseObjectType.TABLE;
                return object(type, rows.getString(1), rows.getString(2), value(rows.getString(4)),
                        Collections.<String, String>emptyMap());
            }
        }
    }

    @Override public List<ObjectIndexInfo> listIndexes(DatabaseSession session, String catalog, String schema,
                                                        String objectName) throws SQLException {
        String owner = upper(schema == null || schema.trim().isEmpty() ? catalog : schema);
        Map<String, OracleIndexBuilder> indexes = new LinkedHashMap<String, OracleIndexBuilder>();
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT i.INDEX_NAME,i.UNIQUENESS,i.INDEX_TYPE,i.STATUS,i.VISIBILITY,i.PARTITIONED,i.TABLESPACE_NAME,"
                        + "c.CONSTRAINT_TYPE,ic.COLUMN_NAME,ic.DESCEND,ic.COLUMN_POSITION "
                        + "FROM ALL_INDEXES i LEFT JOIN ALL_CONSTRAINTS c ON c.OWNER=i.OWNER "
                        + "AND c.TABLE_NAME=i.TABLE_NAME AND c.INDEX_NAME=i.INDEX_NAME "
                        + "LEFT JOIN ALL_IND_COLUMNS ic ON ic.INDEX_OWNER=i.OWNER AND ic.INDEX_NAME=i.INDEX_NAME "
                        + "WHERE i.TABLE_OWNER=? AND i.TABLE_NAME=? ORDER BY i.INDEX_NAME,ic.COLUMN_POSITION")) {
            statement.setString(1, owner); statement.setString(2, upper(objectName));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    OracleIndexBuilder builder = indexes.get(name);
                    if (builder == null) {
                        builder = new OracleIndexBuilder(name, "P".equals(rows.getString(8)),
                                "UNIQUE".equals(rows.getString(2)), value(rows.getString(3)), value(rows.getString(4)),
                                !"INVISIBLE".equals(rows.getString(5)), "YES".equals(rows.getString(6)),
                                value(rows.getString(7)));
                        indexes.put(name, builder);
                    }
                    if (rows.getString(9) != null) builder.columns.add(new ObjectIndexColumnInfo(rows.getString(9), "",
                            value(rows.getString(10)), rows.getInt(11)));
                }
            }
        } catch (SQLException compatibilityFailure) {
            return MetadataAdapter.super.listIndexes(session, catalog, schema, objectName);
        }
        List<ObjectIndexInfo> result = new ArrayList<ObjectIndexInfo>();
        for (OracleIndexBuilder index : indexes.values()) result.add(index.build());
        return Collections.unmodifiableList(result);
    }

    @Override public MetadataPage<ObjectPartitionInfo> listPartitions(DatabaseSession session, String catalog,
                                                                        String schema, String objectName,
                                                                        String pageToken, int pageSize) throws SQLException {
        String owner = upper(schema == null || schema.trim().isEmpty() ? catalog : schema);
        int offset = decodePageToken(pageToken), limit = Math.max(1, Math.min(500, pageSize));
        List<ObjectPartitionInfo> items = new ArrayList<ObjectPartitionInfo>();
        String sql = "SELECT * FROM (SELECT p.PARTITION_NAME,p.PARTITION_POSITION,t.PARTITIONING_TYPE,"
                + "p.HIGH_VALUE,p.TABLESPACE_NAME,p.NUM_ROWS,p.BLOCKS,t.SUBPARTITIONING_TYPE,ROW_NUMBER() OVER "
                + "(ORDER BY p.PARTITION_POSITION) rn FROM ALL_TAB_PARTITIONS p JOIN ALL_PART_TABLES t "
                + "ON t.OWNER=p.TABLE_OWNER AND t.TABLE_NAME=p.TABLE_NAME WHERE p.TABLE_OWNER=? AND p.TABLE_NAME=?) "
                + "WHERE rn>? AND rn<=? ORDER BY rn";
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
            statement.setString(1, owner); statement.setString(2, upper(objectName));
            statement.setInt(3, offset); statement.setInt(4, offset + limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(new ObjectPartitionInfo(value(rows.getString(1)), value(rows.getString(1)),
                        "", rows.getInt(2), value(rows.getString(3)), "", value(rows.getString(4)),
                        value(rows.getString(5)), nullableLong(rows, 6), blocksAsBytes(rows, 7),
                        !"NONE".equalsIgnoreCase(value(rows.getString(8)))));
            }
        } catch (SQLException failure) {
            return new MetadataPage<ObjectPartitionInfo>(Collections.<ObjectPartitionInfo>emptyList(), "", false,
                    "当前Oracle兼容数据库无法读取分区字典：" + value(failure.getMessage()));
        }
        boolean more = items.size() > limit; if (more) items.remove(items.size() - 1);
        return new MetadataPage<ObjectPartitionInfo>(items, more ? encodePageToken(offset + limit) : "", true, "");
    }

    @Override public MetadataPage<ObjectPartitionInfo> listSubpartitions(DatabaseSession session, String catalog,
                                                                           String schema, String objectName,
                                                                           String parentPartition, String pageToken,
                                                                           int pageSize) throws SQLException {
        String owner = upper(schema == null || schema.trim().isEmpty() ? catalog : schema);
        int offset = decodePageToken(pageToken), limit = Math.max(1, Math.min(500, pageSize));
        List<ObjectPartitionInfo> items = new ArrayList<ObjectPartitionInfo>();
        String sql = "SELECT * FROM (SELECT SUBPARTITION_NAME,SUBPARTITION_POSITION,HIGH_VALUE,TABLESPACE_NAME,"
                + "NUM_ROWS,BLOCKS,ROW_NUMBER() OVER (ORDER BY SUBPARTITION_POSITION) rn "
                + "FROM ALL_TAB_SUBPARTITIONS WHERE TABLE_OWNER=? AND TABLE_NAME=? AND PARTITION_NAME=?) "
                + "WHERE rn>? AND rn<=? ORDER BY rn";
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
            statement.setString(1, owner); statement.setString(2, upper(objectName));
            statement.setString(3, upper(parentPartition)); statement.setInt(4, offset); statement.setInt(5, offset + limit + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(new ObjectPartitionInfo(parentPartition + "/" + value(rows.getString(1)),
                        value(rows.getString(1)), parentPartition, rows.getInt(2), "SUBPARTITION", "",
                        value(rows.getString(3)), value(rows.getString(4)), nullableLong(rows, 5),
                        blocksAsBytes(rows, 6), false));
            }
        } catch (SQLException failure) {
            return new MetadataPage<ObjectPartitionInfo>(Collections.<ObjectPartitionInfo>emptyList(), "", false,
                    "当前Oracle兼容数据库无法读取子分区字典：" + value(failure.getMessage()));
        }
        boolean more = items.size() > limit; if (more) items.remove(items.size() - 1);
        return new MetadataPage<ObjectPartitionInfo>(items, more ? encodePageToken(offset + limit) : "", true, "");
    }

    private List<ColumnInfo> jdbcColumns(DatabaseSession session, String owner, String objectName,
                                         Set<String> primary) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        try (ResultSet result = session.jdbcConnection().getMetaData().getColumns(null, owner, objectName, "%")) {
            while (result.next()) {
                String name = result.getString("COLUMN_NAME");
                columns.add(new ColumnInfo(name, result.getString("TYPE_NAME"), result.getInt("COLUMN_SIZE"),
                        result.getInt("DECIMAL_DIGITS"), result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        result.getString("COLUMN_DEF"), containsIgnoreCase(primary, name),
                        result.getInt("ORDINAL_POSITION"), value(result.getString("REMARKS")),
                        yes(result, "IS_AUTOINCREMENT"), yes(result, "IS_GENERATEDCOLUMN")));
            }
        }
        return columns;
    }

    private static boolean yes(ResultSet result, String column) {
        try { return "YES".equalsIgnoreCase(result.getString(column)); }
        catch (SQLException ignored) { return false; }
    }

    @Override public List<ColumnInfo> listCompletionColumns(DatabaseSession session, String catalog, String schema,
                                                             String objectName) throws SQLException {
        String owner = schema == null || schema.trim().isEmpty() ? catalog : schema;
        List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT COLUMN_NAME,DATA_TYPE,DATA_LENGTH,DATA_PRECISION,DATA_SCALE,CHAR_LENGTH,CHAR_USED,COLUMN_ID "
                        + "FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=? ORDER BY COLUMN_ID")) {
            statement.setString(1, upper(owner));
            statement.setString(2, upper(objectName));
            try (ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                    columns.add(new ColumnInfo(result.getString(1), completionTypeNameExact(result),
                            result.getInt(3), result.getInt(5), true, "", false,
                            result.getInt(8), ""));
                }
            }
        }
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
            } catch (SQLException ignored) { /* 降级到 ALL_SOURCE 查询过程、函数和包的源码。 */ }
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

    @Override public void writeRebuildDdl(DatabaseSession session, DatabaseObject object, Writer writer)
            throws SQLException, IOException {
        String ddlType = ddlType(object.type());
        if (!("TABLE".equals(ddlType) || "VIEW".equals(ddlType))) {
            MetadataAdapter.super.writeRebuildDdl(session, object, writer); return;
        }
        try (Statement transform = session.jdbcConnection().createStatement()) {
            transform.execute("BEGIN DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',TRUE); "
                    + "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'CONSTRAINTS',TRUE); "
                    + "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'REF_CONSTRAINTS',TRUE); END;");
        } catch (SQLException compatibilityIgnored) { LOG.debug("DDL转换参数不可用", compatibilityIgnored); }
        writeMetadataClob(session, writer, "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL",
                ddlType, upper(object.name()), upper(object.schema()));
        if (object.type() == DatabaseObjectType.TABLE) {
            List<String> standalone = new ArrayList<String>();
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                    "SELECT i.INDEX_NAME FROM ALL_INDEXES i WHERE i.TABLE_OWNER=? AND i.TABLE_NAME=? "
                            + "AND NOT EXISTS (SELECT 1 FROM ALL_CONSTRAINTS c WHERE c.OWNER=i.OWNER "
                            + "AND c.TABLE_NAME=i.TABLE_NAME AND c.INDEX_NAME=i.INDEX_NAME) ORDER BY i.INDEX_NAME")) {
                statement.setString(1, upper(object.schema())); statement.setString(2, upper(object.name()));
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) standalone.add(rows.getString(1)); }
            }
            for (String index : standalone) {
                writer.write("\n");
                writeMetadataClob(session, writer, "SELECT DBMS_METADATA.GET_DDL('INDEX', ?, ?) FROM DUAL",
                        index, upper(object.schema()));
            }
            try {
                writer.write("\n");
                writeMetadataClob(session, writer,
                        "SELECT DBMS_METADATA.GET_DEPENDENT_DDL('COMMENT', ?, ?) FROM DUAL",
                        upper(object.name()), upper(object.schema()));
            } catch (SQLException noComments) { LOG.debug("对象没有可输出的备注DDL"); }
        }
    }

    protected boolean isSystemSchema(String schema) { return SYSTEM_SCHEMAS.contains(upper(schema)); }

    private List<String> completionSchemas(List<DatabaseNamespace> namespaces) {
        List<String> schemas = new ArrayList<String>();
        for (DatabaseNamespace namespace : namespaces) {
            String schema = requiredSchema(namespace);
            if (!schemas.contains(schema)) schemas.add(schema);
        }
        return schemas;
    }

    private void streamCommentObjects(DatabaseSession session, List<String> schemas,
                                      Set<DatabaseObjectType> types, Set<String> knownObjects,
                                      CompletionMetadataListener listener) throws SQLException {
        List<DatabaseObject> batch = new ArrayList<DatabaseObject>(COMPLETION_OBJECT_BATCH_SIZE);
        for (int start = 0; start < schemas.size(); start += COMPLETION_CHUNK_SIZE) {
            List<String> chunk = schemas.subList(start, Math.min(schemas.size(), start + COMPLETION_CHUNK_SIZE));
            String sql = "SELECT OWNER,TABLE_NAME,TABLE_TYPE,COMMENTS FROM ALL_TAB_COMMENTS WHERE OWNER IN ("
                    + placeholders(chunk.size()) + ") AND TABLE_TYPE IN ('TABLE','VIEW')";
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
                bindSchemas(statement, chunk);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        DatabaseObjectType type = "VIEW".equalsIgnoreCase(rows.getString(3))
                                ? DatabaseObjectType.VIEW : DatabaseObjectType.TABLE;
                        if (!types.contains(type)) continue;
                        String owner = upper(rows.getString(1)); String name = rows.getString(2);
                        if (!knownObjects.add(objectKey(owner, name))) continue;
                        batch.add(object(type, owner, name, value(rows.getString(4)),
                                Collections.<String, String>emptyMap()));
                        if (batch.size() >= COMPLETION_OBJECT_BATCH_SIZE) flushObjects(batch, listener, false);
                    }
                }
            }
        }
        flushObjects(batch, listener, false);
    }

    private void streamSupplementalTables(DatabaseSession session, List<String> schemas,
                                          Set<DatabaseObjectType> types, Set<String> knownObjects,
                                          CompletionMetadataListener listener) throws SQLException {
        if (!types.contains(DatabaseObjectType.TABLE)) return;
        List<DatabaseObject> batch = new ArrayList<DatabaseObject>(COMPLETION_OBJECT_BATCH_SIZE);
        for (int start = 0; start < schemas.size(); start += COMPLETION_CHUNK_SIZE) {
            List<String> chunk = schemas.subList(start, Math.min(schemas.size(), start + COMPLETION_CHUNK_SIZE));
            String sql = "SELECT OWNER,TABLE_NAME FROM ALL_TABLES WHERE OWNER IN ("
                    + placeholders(chunk.size()) + ")";
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
                bindSchemas(statement, chunk);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String owner = upper(rows.getString(1)); String name = rows.getString(2);
                        if (!knownObjects.add(objectKey(owner, name))) continue;
                        batch.add(object(DatabaseObjectType.TABLE, owner, name, "",
                                Collections.<String, String>emptyMap()));
                        if (batch.size() >= COMPLETION_OBJECT_BATCH_SIZE) flushObjects(batch, listener, true);
                    }
                }
            }
        }
        flushObjects(batch, listener, true);
    }

    private void streamColumnComments(DatabaseSession session, List<String> schemas,
                                      Set<String> knownObjects,
                                      CompletionMetadataListener listener) throws SQLException {
        String objectNameColumn = completionCommentObjectNameColumn(session);
        Map<String, ColumnCommentBatch> grouped = new LinkedHashMap<String, ColumnCommentBatch>();
        int batchSize = 0;
        for (int start = 0; start < schemas.size(); start += COMPLETION_CHUNK_SIZE) {
            List<String> chunk = schemas.subList(start, Math.min(schemas.size(), start + COMPLETION_CHUNK_SIZE));
            String sql = "SELECT OWNER," + objectNameColumn + ",COLUMN_NAME,COMMENTS "
                    + "FROM ALL_COL_COMMENTS WHERE OWNER IN (" + placeholders(chunk.size()) + ")";
            try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
                bindSchemas(statement, chunk);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String owner = upper(rows.getString(1)); String table = rows.getString(2);
                        String key = objectKey(owner, table);
                        if (!knownObjects.contains(key)) continue;
                        ColumnCommentBatch values = grouped.get(key);
                        if (values == null) {
                            values = new ColumnCommentBatch(owner, table);
                            grouped.put(key, values);
                        }
                        values.columns.add(new ColumnInfo(rows.getString(3), "", 0, 0,
                                true, "", false, 0, value(rows.getString(4))));
                        batchSize += 1;
                        if (batchSize >= COMPLETION_COLUMN_BATCH_SIZE) {
                            flushColumns(grouped, listener);
                            grouped.clear();
                            batchSize = 0;
                        }
                    }
                }
            }
        }
        flushColumns(grouped, listener);
    }

    private List<DatabaseNamespace> namespacesFromSchemas(List<String> schemas) {
        List<DatabaseNamespace> result = new ArrayList<DatabaseNamespace>(schemas.size());
        for (String schema : schemas) result.add(DatabaseNamespace.schema(schema, false, isSystemSchema(schema)));
        return result;
    }

    private String completionCommentObjectNameColumn(DatabaseSession session) throws SQLException {
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(
                "SELECT * FROM ALL_COL_COMMENTS WHERE 1=0");
             ResultSet rows = statement.executeQuery()) {
            java.sql.ResultSetMetaData metadata = rows.getMetaData();
            boolean objectName = false;
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
                String label = upper(metadata.getColumnLabel(index));
                if ("TABLE_NAME".equals(label)) return "TABLE_NAME";
                if ("OBJECT_NAME".equals(label)) objectName = true;
            }
            if (objectName) return "OBJECT_NAME";
        }
        throw new SQLException("ALL_COL_COMMENTS缺少TABLE_NAME或OBJECT_NAME列");
    }

    private static String completionTypeNameExact(ResultSet rows) throws SQLException {
        String type = value(rows.getString(2));
        int charLength = rows.getInt(6);
        if (("CHAR".equalsIgnoreCase(type) || "NCHAR".equalsIgnoreCase(type)
                || "VARCHAR2".equalsIgnoreCase(type) || "NVARCHAR2".equalsIgnoreCase(type)) && charLength > 0) {
            String semantics = "C".equalsIgnoreCase(value(rows.getString(7))) ? " CHAR" : "";
            return type + "(" + charLength + semantics + ")";
        }
        Object precision = rows.getObject(4);
        if ("NUMBER".equalsIgnoreCase(type) && precision != null) {
            int scale = rows.getInt(5);
            return type + "(" + rows.getInt(4) + (scale != 0 ? "," + scale : "") + ")";
        }
        return type;
    }

    private static void flushObjects(List<DatabaseObject> batch, CompletionMetadataListener listener,
                                     boolean supplemental) throws SQLException {
        if (batch.isEmpty()) return;
        List<DatabaseObject> values = Collections.unmodifiableList(new ArrayList<DatabaseObject>(batch));
        if (supplemental) listener.supplementalTables(values); else listener.objects(values);
        batch.clear();
    }

    private static void flushColumns(Map<String, ColumnCommentBatch> grouped,
                                     CompletionMetadataListener listener) throws SQLException {
        List<CompletionColumnComments> groups = new ArrayList<CompletionColumnComments>(grouped.size());
        for (ColumnCommentBatch batch : grouped.values()) {
            groups.add(new CompletionColumnComments("", batch.schema, batch.objectName, batch.columns));
        }
        if (!groups.isEmpty()) listener.columns(Collections.unmodifiableList(groups));
    }

    private static String placeholders(int count) {
        StringBuilder values = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) values.append(',');
            values.append('?');
        }
        return values.toString();
    }

    private static void bindSchemas(PreparedStatement statement, List<String> schemas) throws SQLException {
        for (int index = 0; index < schemas.size(); index++) statement.setString(index + 1, schemas.get(index));
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
    private static void writeMetadataClob(DatabaseSession session, Writer writer, String sql, String... parameters)
            throws SQLException, IOException {
        try (PreparedStatement statement = session.jdbcConnection().prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) statement.setString(index + 1, parameters[index]);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("数据库未返回对象定义");
                Object value = rows.getObject(1);
                if (!(value instanceof Clob)) { writer.write(value == null ? "" : String.valueOf(value)); return; }
                try (Reader reader = ((Clob) value).getCharacterStream()) {
                    char[] buffer = new char[8192]; int read;
                    while ((read = reader.read(buffer)) >= 0) writer.write(buffer, 0, read);
                }
            }
        }
    }
    private static Long nullableLong(ResultSet rows, int index) throws SQLException {
        long number = rows.getLong(index); return rows.wasNull() ? null : Long.valueOf(number);
    }
    private static Long blocksAsBytes(ResultSet rows, int index) throws SQLException {
        Long blocks = nullableLong(rows, index); return blocks == null ? null : Long.valueOf(blocks.longValue() * 8192L);
    }
    private static String encodePageToken(int offset) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                String.valueOf(offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static int decodePageToken(String token) throws SQLException {
        if (token == null || token.isEmpty()) return 0;
        try { return Integer.parseInt(new String(java.util.Base64.getUrlDecoder().decode(token),
                java.nio.charset.StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw new SQLException("分页标识无效", invalid); }
    }
    private static boolean containsIgnoreCase(Set<String> values, String candidate) {
        for (String value : values) if (value.equalsIgnoreCase(candidate)) return true;
        return false;
    }
    private static String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private static String value(String value) { return value == null ? "" : value; }
    private static final class ColumnCommentBatch {
        private final String schema;
        private final String objectName;
        private final List<ColumnInfo> columns = new ArrayList<ColumnInfo>();
        private ColumnCommentBatch(String schema, String objectName) {
            this.schema = schema;
            this.objectName = objectName;
        }
    }
    private static final class KeyBuilder {
        private final String name; private final boolean primary;
        private final TreeMap<Integer, String> columns = new TreeMap<Integer, String>();
        private KeyBuilder(String name, boolean primary) { this.name=name; this.primary=primary; }
    }
    private static final class OracleIndexBuilder {
        private final String name; private final boolean primary; private final boolean unique;
        private final String type; private final String status; private final boolean visible;
        private final boolean partitioned; private final String tablespace;
        private final List<ObjectIndexColumnInfo> columns = new ArrayList<ObjectIndexColumnInfo>();
        private OracleIndexBuilder(String name, boolean primary, boolean unique, String type, String status,
                                   boolean visible, boolean partitioned, String tablespace) {
            this.name=name; this.primary=primary; this.unique=unique; this.type=type; this.status=status;
            this.visible=visible; this.partitioned=partitioned; this.tablespace=tablespace;
        }
        private ObjectIndexInfo build() {
            return new ObjectIndexInfo(name, primary, unique, type, status, visible, partitioned, tablespace, columns);
        }
    }
}
