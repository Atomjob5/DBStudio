package com.dbstudio.desktop.completion;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.CompletionObjectInfo;
import com.dbstudio.spi.DatabaseCapability;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseNamespace;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.DatabaseSession;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 补全快照生成器。
 *
 * <p>服务只依赖数据库 SPI，不持有连接生命周期；调用方负责提供临时元数据会话并在完成后关闭。
 * 生成过程中通过进度回调报告阶段，只有全部对象成功枚举后才返回不可变快照。</p>
 */
public final class CompletionSnapshotService {
    private static final Logger LOG = LoggerFactory.getLogger(CompletionSnapshotService.class);

    public Snapshot build(DatabaseProvider provider, DatabaseSession session, String sourceProfileId,
                          ProgressListener progress) throws SQLException {
        long started = System.nanoTime();
        LOG.info("开始生成SQL补全快照 provider={} sourceProfile={}", provider.id(), sourceProfileId);
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        List<Suggestion> suggestions = new ArrayList<Suggestion>();
        Set<String> identities = new LinkedHashSet<String>();

        List<String> keywords = new ArrayList<String>(provider.dialect().keywords());
        Collections.sort(keywords);
        for (String keyword : keywords) {
            add(suggestions, identities, suggestion("keyword", "", "", "", keyword,
                    keyword, keyword, provider.displayName() + " 关键字", ""));
        }

        listener.progress("discovering", 0, 0, "正在扫描可见数据库命名空间…");
        List<DatabaseNamespace> namespaces = provider.metadata().listNamespaces(session);
        Set<DatabaseObjectType> requestedTypes = new LinkedHashSet<DatabaseObjectType>();
        for (int namespaceIndex = 0; namespaceIndex < namespaces.size(); namespaceIndex++) {
            DatabaseNamespace namespace = namespaces.get(namespaceIndex);
            String namespaceKind = namespace.kind() == com.dbstudio.spi.NamespaceKind.SCHEMA ? "schema" : "database";
            add(suggestions, identities, suggestion(namespaceKind, namespace.catalog(), namespace.schema(), "",
                    namespace.label(), namespace.label(), provider.dialect().quoteIdentifier(namespace.label()),
                    ("schema".equals(namespaceKind) ? "Schema " : "数据库 ") + namespace.label(), ""));
            listener.progress("discovering", namespaceIndex + 1, namespaces.size(), "已扫描 " + namespace.label());
        }
        request(provider, requestedTypes, DatabaseObjectType.TABLE, DatabaseCapability.TABLES);
        request(provider, requestedTypes, DatabaseObjectType.VIEW, DatabaseCapability.VIEWS);
        request(provider, requestedTypes, DatabaseObjectType.FUNCTION, DatabaseCapability.FUNCTIONS);
        request(provider, requestedTypes, DatabaseObjectType.PROCEDURE, DatabaseCapability.PROCEDURES);
        request(provider, requestedTypes, DatabaseObjectType.SEQUENCE, DatabaseCapability.SEQUENCES);
        List<CompletionObjectInfo> objects = new ArrayList<CompletionObjectInfo>(
                provider.metadata().listCompletionObjects(session, namespaces, requestedTypes));

        Collections.sort(objects, new Comparator<CompletionObjectInfo>() {
            @Override public int compare(CompletionObjectInfo left, CompletionObjectInfo right) {
                int catalog = left.object().catalog().compareToIgnoreCase(right.object().catalog());
                if (catalog != 0) return catalog;
                int schema = left.object().schema().compareToIgnoreCase(right.object().schema());
                if (schema != 0) return schema;
                int type = left.object().type().name().compareTo(right.object().type().name());
                return type != 0 ? type : left.object().name().compareToIgnoreCase(right.object().name());
            }
        });
        for (int index = 0; index < objects.size(); index++) {
            CompletionObjectInfo completionObject = objects.get(index);
            DatabaseObject object = completionObject.object();
            String kind = kind(object.type());
            String detail = qualified(object.catalog(), object.schema(), object.name()) + " · " + object.type().displayName();
            if (!object.remarks().trim().isEmpty()) detail += " · " + object.remarks().trim();
            add(suggestions, identities, suggestion(kind, object.catalog(), object.schema(), object.name(),
                    object.name(), object.name(), provider.dialect().quoteIdentifier(object.name()),
                    detail, object.remarks()));

            if (object.type() == DatabaseObjectType.TABLE || object.type() == DatabaseObjectType.VIEW) {
                for (ColumnInfo column : completionObject.columns()) {
                    String columnDetail = qualified(object.catalog(), object.schema(), object.name()) + " · " + column.typeName();
                    if (column.primaryKey()) columnDetail += " · 主键";
                    if (!column.remarks().trim().isEmpty()) columnDetail += " · " + column.remarks().trim();
                    add(suggestions, identities, suggestion("column", object.catalog(), object.schema(),
                            object.name(), column.name(), column.name(),
                            provider.dialect().quoteIdentifier(column.name()), columnDetail, column.remarks()));
                }
            }
            listener.progress("loading", index + 1, objects.size(), qualified(object.catalog(), object.schema(), object.name()));
        }
        Snapshot snapshot = new Snapshot(provider.id(), sourceProfileId, Instant.now().toString(), suggestions);
        LOG.info("SQL补全快照生成完成 provider={} sourceProfile={} namespaces={} objects={} suggestions={} durationMs={}",
                provider.id(), sourceProfileId, namespaces.size(), objects.size(), suggestions.size(),
                (System.nanoTime() - started) / 1_000_000L);
        return snapshot;
    }

    private static void request(DatabaseProvider provider, Set<DatabaseObjectType> target,
                                DatabaseObjectType type, DatabaseCapability capability) {
        if (provider.capabilities().supports(capability)) target.add(type);
    }

    private static String kind(DatabaseObjectType type) {
        if (type == DatabaseObjectType.VIEW) return "view";
        if (type == DatabaseObjectType.FUNCTION) return "function";
        if (type == DatabaseObjectType.PROCEDURE) return "procedure";
        if (type == DatabaseObjectType.SEQUENCE) return "sequence";
        return "table";
    }

    private static Suggestion suggestion(String kind, String catalog, String schema, String parent,
                                         String identityName, String label, String insertText,
                                         String detail, String remarks) {
        String rawIdentity = kind + "|" + catalog + "|" + schema + "|" + parent + "|" + identityName;
        String id = UUID.nameUUIDFromBytes(rawIdentity.getBytes(StandardCharsets.UTF_8)).toString();
        return new Suggestion(id, label, insertText, detail, kind, catalog, schema, parent, remarks);
    }

    private static void add(List<Suggestion> target, Set<String> identities, Suggestion suggestion) {
        if (identities.add(suggestion.id())) target.add(suggestion);
    }

    private static String qualified(String catalog, String schema, String name) {
        String namespace = schema == null || schema.trim().isEmpty() ? catalog : schema;
        return namespace == null || namespace.trim().isEmpty() ? name : namespace + "." + name;
    }

    public interface ProgressListener {
        ProgressListener NONE = new ProgressListener() {
            @Override public void progress(String phase, int completed, int total, String message) { }
        };
        void progress(String phase, int completed, int total, String message);
    }

    public static final class Snapshot {
        private final String providerId;
        private final String sourceProfileId;
        private final String generatedAt;
        private final List<Suggestion> suggestions;

        public Snapshot(String providerId, String sourceProfileId, String generatedAt,
                        List<Suggestion> suggestions) {
            this.providerId = providerId;
            this.sourceProfileId = sourceProfileId;
            this.generatedAt = generatedAt;
            this.suggestions = Collections.unmodifiableList(new ArrayList<Suggestion>(suggestions));
        }
        public String providerId() { return providerId; }
        public String sourceProfileId() { return sourceProfileId; }
        public String generatedAt() { return generatedAt; }
        public List<Suggestion> suggestions() { return suggestions; }
    }

    public static final class Suggestion {
        private final String id;
        private final String label;
        private final String insertText;
        private final String detail;
        private final String kind;
        private final String catalog;
        private final String schema;
        private final String objectName;
        private final String remarks;

        public Suggestion(String id, String label, String insertText, String detail, String kind,
                          String catalog, String schema, String objectName, String remarks) {
            this.id = id; this.label = label; this.insertText = insertText; this.detail = detail;
            this.kind = kind; this.catalog = catalog == null ? "" : catalog;
            this.schema = schema == null ? "" : schema;
            this.objectName = objectName == null ? "" : objectName;
            this.remarks = remarks == null ? "" : remarks;
        }
        public String id() { return id; }
        public String label() { return label; }
        public String insertText() { return insertText; }
        public String detail() { return detail; }
        public String kind() { return kind; }
        public String catalog() { return catalog; }
        public String schema() { return schema; }
        public String objectName() { return objectName; }
        public String remarks() { return remarks; }
    }
}
