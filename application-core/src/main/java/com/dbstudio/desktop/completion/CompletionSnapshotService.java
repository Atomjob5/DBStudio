package com.dbstudio.desktop.completion;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.DatabaseCapability;
import com.dbstudio.spi.DatabaseObject;
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

/** Builds a database-independent, immutable completion snapshot from a metadata session. */
public final class CompletionSnapshotService {
    public Snapshot build(DatabaseProvider provider, DatabaseSession session, String sourceProfileId,
                          ProgressListener progress) throws SQLException {
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        List<Suggestion> suggestions = new ArrayList<Suggestion>();
        Set<String> identities = new LinkedHashSet<String>();

        List<String> keywords = new ArrayList<String>(provider.dialect().keywords());
        Collections.sort(keywords);
        for (String keyword : keywords) {
            add(suggestions, identities, suggestion("keyword", "", "", "", keyword,
                    keyword, keyword, provider.displayName() + " 关键字", ""));
        }

        listener.progress("discovering", 0, 0, "正在扫描可见数据库…");
        List<String> catalogs = provider.metadata().listCatalogs(session);
        List<ObjectEntry> objects = new ArrayList<ObjectEntry>();
        for (int catalogIndex = 0; catalogIndex < catalogs.size(); catalogIndex++) {
            String catalog = catalogs.get(catalogIndex);
            add(suggestions, identities, suggestion("database", catalog, "", "", catalog,
                    catalog, provider.dialect().quoteIdentifier(catalog), "数据库 " + catalog, ""));
            collect(provider, session, catalog, DatabaseObjectType.TABLE, DatabaseCapability.TABLES, objects);
            collect(provider, session, catalog, DatabaseObjectType.VIEW, DatabaseCapability.VIEWS, objects);
            collect(provider, session, catalog, DatabaseObjectType.FUNCTION, DatabaseCapability.FUNCTIONS, objects);
            collect(provider, session, catalog, DatabaseObjectType.PROCEDURE, DatabaseCapability.PROCEDURES, objects);
            listener.progress("discovering", catalogIndex + 1, catalogs.size(), "已扫描 " + catalog);
        }

        Collections.sort(objects, new Comparator<ObjectEntry>() {
            @Override public int compare(ObjectEntry left, ObjectEntry right) {
                int catalog = left.object.catalog().compareToIgnoreCase(right.object.catalog());
                if (catalog != 0) return catalog;
                int type = left.object.type().name().compareTo(right.object.type().name());
                return type != 0 ? type : left.object.name().compareToIgnoreCase(right.object.name());
            }
        });
        for (int index = 0; index < objects.size(); index++) {
            DatabaseObject object = objects.get(index).object;
            String kind = kind(object.type());
            String detail = qualified(object.catalog(), object.name()) + " · " + object.type().displayName();
            if (!object.remarks().trim().isEmpty()) detail += " · " + object.remarks().trim();
            add(suggestions, identities, suggestion(kind, object.catalog(), object.schema(), object.name(),
                    object.name(), object.name(), provider.dialect().quoteIdentifier(object.name()),
                    detail, object.remarks()));

            if (object.type() == DatabaseObjectType.TABLE || object.type() == DatabaseObjectType.VIEW) {
                for (ColumnInfo column : provider.metadata().listColumns(
                        session, object.catalog(), object.schema(), object.name())) {
                    String columnDetail = qualified(object.catalog(), object.name()) + " · " + column.typeName();
                    if (column.primaryKey()) columnDetail += " · 主键";
                    if (!column.remarks().trim().isEmpty()) columnDetail += " · " + column.remarks().trim();
                    add(suggestions, identities, suggestion("column", object.catalog(), object.schema(),
                            object.name(), column.name(), column.name(),
                            provider.dialect().quoteIdentifier(column.name()), columnDetail, column.remarks()));
                }
            }
            listener.progress("loading", index + 1, objects.size(), qualified(object.catalog(), object.name()));
        }
        return new Snapshot(provider.id(), sourceProfileId, Instant.now().toString(), suggestions);
    }

    private static void collect(DatabaseProvider provider, DatabaseSession session, String catalog,
                                DatabaseObjectType type, DatabaseCapability capability,
                                List<ObjectEntry> target) throws SQLException {
        if (!provider.capabilities().supports(capability)) return;
        for (DatabaseObject object : provider.metadata().listObjects(session, catalog, type)) {
            target.add(new ObjectEntry(object));
        }
    }

    private static String kind(DatabaseObjectType type) {
        if (type == DatabaseObjectType.VIEW) return "view";
        if (type == DatabaseObjectType.FUNCTION) return "function";
        if (type == DatabaseObjectType.PROCEDURE) return "procedure";
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

    private static String qualified(String catalog, String name) {
        return catalog == null || catalog.trim().isEmpty() ? name : catalog + "." + name;
    }

    private static final class ObjectEntry {
        private final DatabaseObject object;
        private ObjectEntry(DatabaseObject object) { this.object = object; }
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
