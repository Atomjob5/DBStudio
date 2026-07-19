package com.dbstudio.desktop.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.ConnectionAdapter;
import com.dbstudio.spi.ConnectionField;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.ConnectionTestResult;
import com.dbstudio.spi.DatabaseCapabilities;
import com.dbstudio.spi.DatabaseCapability;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletionSnapshotServiceTest {
    @Test
    void buildsACompleteStableSnapshotAcrossVisibleCatalogs() throws Exception {
        final List<String> progress = new ArrayList<String>();
        CompletionSnapshotService service = new CompletionSnapshotService();
        CompletionSnapshotService.Snapshot first = service.build(provider(), session(), "profile-1",
                new CompletionSnapshotService.ProgressListener() {
                    @Override public void progress(String phase, int completed, int total, String message) {
                        progress.add(phase + ":" + completed + "/" + total + ":" + message);
                    }
                });
        CompletionSnapshotService.Snapshot second = service.build(provider(), session(), "profile-1", null);

        assertEquals("fake", first.providerId());
        assertEquals("profile-1", first.sourceProfileId());
        assertTrue(first.suggestions().stream().anyMatch(value -> "keyword".equals(value.kind()) && "SELECT".equals(value.label())));
        assertTrue(first.suggestions().stream().anyMatch(value -> "database".equals(value.kind()) && "sales".equals(value.label())));
        assertTrue(first.suggestions().stream().anyMatch(value -> "view".equals(value.kind()) && "orders_view".equals(value.label())));
        assertTrue(first.suggestions().stream().anyMatch(value -> "function".equals(value.kind()) && "total_amount".equals(value.label())));
        assertTrue(first.suggestions().stream().anyMatch(value -> "procedure".equals(value.kind()) && "archive_orders".equals(value.label())));

        List<CompletionSnapshotService.Suggestion> ids = find(first, "column", "id");
        assertEquals(2, ids.size(), "不同表里的同名字段必须保留");
        assertNotEquals(ids.get(0).id(), ids.get(1).id());
        assertTrue(ids.stream().anyMatch(value -> "订单编号".equals(value.remarks())));
        assertEquals(first.suggestions().size(), new HashSet<String>(suggestionIds(first)).size());
        assertEquals(suggestionIds(first), suggestionIds(second), "相同元数据必须生成稳定身份");
        assertFalse(progress.isEmpty());
        assertTrue(progress.stream().anyMatch(value -> value.startsWith("discovering:")));
        assertTrue(progress.stream().anyMatch(value -> value.startsWith("loading:")));
    }

    private static List<CompletionSnapshotService.Suggestion> find(
            CompletionSnapshotService.Snapshot snapshot, String kind, String label) {
        List<CompletionSnapshotService.Suggestion> values = new ArrayList<CompletionSnapshotService.Suggestion>();
        for (CompletionSnapshotService.Suggestion value : snapshot.suggestions()) {
            if (kind.equals(value.kind()) && label.equals(value.label())) values.add(value);
        }
        return values;
    }

    private static List<String> suggestionIds(CompletionSnapshotService.Snapshot snapshot) {
        List<String> values = new ArrayList<String>();
        for (CompletionSnapshotService.Suggestion suggestion : snapshot.suggestions()) values.add(suggestion.id());
        return values;
    }

    private static DatabaseProvider provider() {
        final MetadataAdapter metadata = new MetadataAdapter() {
            @Override public List<String> listCatalogs(DatabaseSession ignored) {
                return Arrays.asList("sales", "reporting");
            }

            @Override public List<DatabaseObject> listObjects(
                    DatabaseSession ignored, String catalog, DatabaseObjectType type) {
                if ("sales".equals(catalog) && type == DatabaseObjectType.TABLE) {
                    return Arrays.asList(object(type, catalog, "orders", "订单"), object(type, catalog, "customers", "客户"));
                }
                if ("reporting".equals(catalog) && type == DatabaseObjectType.VIEW) {
                    return Collections.singletonList(object(type, catalog, "orders_view", "订单视图"));
                }
                if ("sales".equals(catalog) && type == DatabaseObjectType.FUNCTION) {
                    return Collections.singletonList(object(type, catalog, "total_amount", ""));
                }
                if ("sales".equals(catalog) && type == DatabaseObjectType.PROCEDURE) {
                    return Collections.singletonList(object(type, catalog, "archive_orders", ""));
                }
                return Collections.emptyList();
            }

            @Override public List<ColumnInfo> listColumns(
                    DatabaseSession ignored, String catalog, String schema, String objectName) {
                if ("orders".equals(objectName)) {
                    return Arrays.asList(new ColumnInfo("id", "BIGINT", 20, 0, false, null, true, 1, "订单编号"),
                            new ColumnInfo("amount", "DECIMAL", 18, 2, false, null, false, 2, "金额"));
                }
                if ("customers".equals(objectName)) {
                    return Collections.singletonList(new ColumnInfo("id", "BIGINT", 20, 0, false, null, true, 1, "客户编号"));
                }
                return Collections.singletonList(new ColumnInfo("order_id", "BIGINT", 20, 0, false, null, false, 1));
            }

            @Override public String definition(DatabaseSession ignored, DatabaseObject object) { return ""; }
        };
        final SqlDialect dialect = new SqlDialect() {
            @Override public String id() { return "fake"; }
            @Override public String quoteIdentifier(String identifier) { return "`" + identifier + "`"; }
            @Override public Set<String> keywords() { return new HashSet<String>(Arrays.asList("SELECT", "FROM")); }
            @Override public List<SqlStatement> split(String script) { return Collections.emptyList(); }
            @Override public Optional<SqlStatement> currentStatement(String script, int cursorOffset) { return Optional.empty(); }
            @Override public StatementType classify(String sql) { return StatementType.QUERY; }
            @Override public String format(String sql) { return sql; }
        };
        return new DatabaseProvider() {
            @Override public String id() { return "fake"; }
            @Override public String displayName() { return "Fake SQL"; }
            @Override public List<ConnectionField> connectionFields() { return Collections.emptyList(); }
            @Override public DatabaseCapabilities capabilities() {
                return DatabaseCapabilities.of(DatabaseCapability.TABLES, DatabaseCapability.VIEWS,
                        DatabaseCapability.FUNCTIONS, DatabaseCapability.PROCEDURES);
            }
            @Override public ConnectionAdapter connections() {
                return new ConnectionAdapter() {
                    @Override public ConnectionTestResult test(ConnectionProfile profile, char[] password) {
                        return new ConnectionTestResult(true, "ok", "fake", Duration.ZERO);
                    }
                    @Override public DatabaseSession connect(ConnectionProfile profile, char[] password) { return session(); }
                };
            }
            @Override public MetadataAdapter metadata() { return metadata; }
            @Override public SqlDialect dialect() { return dialect; }
        };
    }

    private static DatabaseObject object(DatabaseObjectType type, String catalog, String name, String remarks) {
        return new DatabaseObject(type, catalog, "", name, remarks, Collections.<String, String>emptyMap());
    }

    private static DatabaseSession session() {
        return new DatabaseSession() {
            @Override public Connection jdbcConnection() { return null; }
            @Override public String currentCatalog() { return "sales"; }
            @Override public void close() { }
        };
    }
}
