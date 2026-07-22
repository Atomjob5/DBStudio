package com.dbstudio.desktop.completion;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.CompletionObjectInfo;
import com.dbstudio.spi.DatabaseNamespace;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.DatabaseSession;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Builds a compact, hierarchical SQL-completion metadata snapshot. */
public final class CompletionSnapshotService {
    public static final int FORMAT_VERSION = 1;
    private static final Logger LOG = LoggerFactory.getLogger(CompletionSnapshotService.class);

    /** Compatibility overload used by callers that intentionally want every normal namespace. */
    public Snapshot build(DatabaseProvider provider, DatabaseSession session, String sourceProfileId,
                          ProgressListener progress) throws SQLException {
        return build(provider, session, sourceProfileId, provider.metadata().listNamespaces(session), progress);
    }

    public Snapshot build(DatabaseProvider provider, DatabaseSession session, String sourceProfileId,
                          List<DatabaseNamespace> selectedNamespaces, ProgressListener progress) throws SQLException {
        long started = System.nanoTime();
        ProgressListener listener = new ThrottledProgressListener(progress == null ? ProgressListener.NONE : progress);
        List<DatabaseNamespace> selected = Collections.unmodifiableList(
                new ArrayList<DatabaseNamespace>(selectedNamespaces == null
                        ? Collections.<DatabaseNamespace>emptyList() : selectedNamespaces));
        if (selected.isEmpty()) throw new IllegalArgumentException("至少选择一个Schema用于SQL补全");

        Map<String, NamespaceBuilder> builders = new LinkedHashMap<String, NamespaceBuilder>();
        String defaultNamespaceKey = "";
        for (DatabaseNamespace namespace : selected) {
            String key = namespaceKey(namespace.catalog(), namespace.schema());
            builders.put(key, new NamespaceBuilder(key, namespace));
            if (defaultNamespaceKey.isEmpty() && namespace.current()) defaultNamespaceKey = key;
        }

        listener.progress("loading", 0, 0, "正在批量读取表、视图和字段…");
        Set<DatabaseObjectType> types = new LinkedHashSet<DatabaseObjectType>();
        types.add(DatabaseObjectType.TABLE);
        types.add(DatabaseObjectType.VIEW);
        List<CompletionObjectInfo> values = new ArrayList<CompletionObjectInfo>(
                provider.metadata().listCompletionObjects(session, selected, types,
                        new com.dbstudio.spi.MetadataAdapter.CompletionLoadListener() {
                            @Override public void compatibilityFallback(String message) {
                                listener.progress("loading", 0, 0, message);
                            }
                        }));
        Collections.sort(values, new Comparator<CompletionObjectInfo>() {
            @Override public int compare(CompletionObjectInfo left, CompletionObjectInfo right) {
                DatabaseObject a = left.object(); DatabaseObject b = right.object();
                int namespace = namespaceKey(a.catalog(), a.schema()).compareToIgnoreCase(
                        namespaceKey(b.catalog(), b.schema()));
                if (namespace != 0) return namespace;
                int type = a.type().name().compareTo(b.type().name());
                return type != 0 ? type : a.name().compareToIgnoreCase(b.name());
            }
        });

        int columnCount = 0;
        for (int index = 0; index < values.size(); index++) {
            CompletionObjectInfo value = values.get(index);
            DatabaseObject object = value.object();
            NamespaceBuilder namespace = builders.get(namespaceKey(object.catalog(), object.schema()));
            if (namespace == null || (object.type() != DatabaseObjectType.TABLE
                    && object.type() != DatabaseObjectType.VIEW)) continue;
            List<ColumnSnapshot> columns = new ArrayList<ColumnSnapshot>(value.columns().size());
            for (ColumnInfo column : value.columns()) {
                columns.add(new ColumnSnapshot(column.name(), column.typeName(), column.remarks()));
            }
            columnCount += columns.size();
            namespace.objects.add(new ObjectSnapshot(object.name(),
                    object.type() == DatabaseObjectType.VIEW ? "view" : "table", object.remarks(), columns));
            listener.progress("loading", index + 1, values.size(), qualified(object));
        }

        List<NamespaceSnapshot> namespaces = new ArrayList<NamespaceSnapshot>(builders.size());
        List<String> selectedKeys = new ArrayList<String>(builders.size());
        for (NamespaceBuilder builder : builders.values()) {
            namespaces.add(builder.snapshot());
            selectedKeys.add(builder.key);
        }
        listener.progress("loading", values.size(), values.size(), "补全缓存已生成");
        Snapshot snapshot = new Snapshot(FORMAT_VERSION, provider.id(), sourceProfileId,
                Instant.now().toString(), defaultNamespaceKey, selectedKeys, namespaces);
        LOG.info("SQL补全快照生成完成 provider={} sourceProfile={} namespaces={} objects={} columns={} durationMs={}",
                provider.id(), sourceProfileId, namespaces.size(), values.size(), columnCount,
                (System.nanoTime() - started) / 1_000_000L);
        return snapshot;
    }

    public static String namespaceKey(String catalog, String schema) {
        String normalizedSchema = value(schema);
        return normalizedSchema.isEmpty() ? "catalog:" + value(catalog) : "schema:" + normalizedSchema;
    }

    private static String qualified(DatabaseObject object) {
        String namespace = object.schema().isEmpty() ? object.catalog() : object.schema();
        return namespace.isEmpty() ? object.name() : namespace + "." + object.name();
    }

    private static String value(String value) { return value == null ? "" : value; }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
            @Override public void progress(String phase, int completed, int total, String message) { }
        };
        void progress(String phase, int completed, int total, String message);
    }

    private static final class ThrottledProgressListener implements ProgressListener {
        private static final long INTERVAL_NANOS = 250_000_000L;
        private final ProgressListener delegate;
        private String lastPhase = "";
        private long lastEmission;

        private ThrottledProgressListener(ProgressListener delegate) { this.delegate = delegate; }

        @Override public void progress(String phase, int completed, int total, String message) {
            long now = System.nanoTime();
            boolean boundary = !phase.equals(lastPhase) || completed == 0 || total > 0 && completed >= total;
            if (!boundary && now - lastEmission < INTERVAL_NANOS) return;
            lastPhase = phase; lastEmission = now;
            delegate.progress(phase, completed, total, message);
        }
    }

    private static final class NamespaceBuilder {
        private final String key;
        private final DatabaseNamespace namespace;
        private final List<ObjectSnapshot> objects = new ArrayList<ObjectSnapshot>();
        private NamespaceBuilder(String key, DatabaseNamespace namespace) { this.key = key; this.namespace = namespace; }
        private NamespaceSnapshot snapshot() {
            return new NamespaceSnapshot(key, namespace.catalog(), namespace.schema(), namespace.label(), objects);
        }
    }

    public static final class Snapshot {
        private final int formatVersion;
        private final String providerId;
        private final String sourceProfileId;
        private final String generatedAt;
        private final String defaultNamespaceKey;
        private final List<String> selectedNamespaceKeys;
        private final List<NamespaceSnapshot> namespaces;
        public Snapshot(int formatVersion, String providerId, String sourceProfileId, String generatedAt,
                        String defaultNamespaceKey, List<String> selectedNamespaceKeys,
                        List<NamespaceSnapshot> namespaces) {
            this.formatVersion = formatVersion; this.providerId = providerId;
            this.sourceProfileId = sourceProfileId; this.generatedAt = generatedAt;
            this.defaultNamespaceKey = value(defaultNamespaceKey);
            this.selectedNamespaceKeys = Collections.unmodifiableList(new ArrayList<String>(selectedNamespaceKeys));
            this.namespaces = Collections.unmodifiableList(new ArrayList<NamespaceSnapshot>(namespaces));
        }
        public int formatVersion() { return formatVersion; }
        public String providerId() { return providerId; }
        public String sourceProfileId() { return sourceProfileId; }
        public String generatedAt() { return generatedAt; }
        public String defaultNamespaceKey() { return defaultNamespaceKey; }
        public List<String> selectedNamespaceKeys() { return selectedNamespaceKeys; }
        public List<NamespaceSnapshot> namespaces() { return namespaces; }
    }

    public static final class NamespaceSnapshot {
        private final String key, catalog, schema, label;
        private final List<ObjectSnapshot> objects;
        public NamespaceSnapshot(String key, String catalog, String schema, String label,
                                 List<ObjectSnapshot> objects) {
            this.key = value(key); this.catalog = value(catalog); this.schema = value(schema); this.label = value(label);
            this.objects = Collections.unmodifiableList(new ArrayList<ObjectSnapshot>(objects));
        }
        public String key() { return key; }
        public String catalog() { return catalog; }
        public String schema() { return schema; }
        public String label() { return label; }
        public List<ObjectSnapshot> objects() { return objects; }
    }

    public static final class ObjectSnapshot {
        private final String name, kind, remarks;
        private final List<ColumnSnapshot> columns;
        public ObjectSnapshot(String name, String kind, String remarks, List<ColumnSnapshot> columns) {
            this.name = value(name); this.kind = value(kind); this.remarks = value(remarks);
            this.columns = Collections.unmodifiableList(new ArrayList<ColumnSnapshot>(columns));
        }
        public String name() { return name; }
        public String kind() { return kind; }
        public String remarks() { return remarks; }
        public List<ColumnSnapshot> columns() { return columns; }
    }

    public static final class ColumnSnapshot {
        private final String name, typeName, remarks;
        public ColumnSnapshot(String name, String typeName, String remarks) {
            this.name = value(name); this.typeName = value(typeName); this.remarks = value(remarks);
        }
        public String name() { return name; }
        public String typeName() { return typeName; }
        public String remarks() { return remarks; }
    }
}
