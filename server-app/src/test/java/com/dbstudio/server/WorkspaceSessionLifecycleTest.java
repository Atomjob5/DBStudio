package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.desktop.DatabaseContext;
import com.dbstudio.desktop.persistence.ConnectionProfileRepository.SavedProfile;
import com.dbstudio.desktop.web.EditorSessionRegistry.EditorSession;
import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.ConnectionAdapter;
import com.dbstudio.spi.ConnectionField;
import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.ConnectionTestResult;
import com.dbstudio.spi.DatabaseCapabilities;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSessionLifecycleTest {
    @TempDir Path directory;

    @Test
    void safelySuspendsAndReopensAStillBoundEditorSession() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        DatabaseProvider provider = provider(opened);
        ConnectionProfile profile = new ConnectionProfile(
                UUID.randomUUID(), "fake", "测试链接", Collections.<String, String>emptyMap(), "test-secret");
        SavedProfile saved = new SavedProfile(profile, false, "environment", "revision-1");
        EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        limiter.setMaximum(10);
        Workspace workspace = new Workspace(UUID.randomUUID().toString(), 1000, 100,
                new ObjectMapper(), directory, limiter);
        try {
            DatabaseContext context = new DatabaseContext(provider, profile, new char[0]);
            workspace.registerContext(Workspace.bindingKey(saved), context);
            EditorSession editor = workspace.editors().create();
            workspace.bind(editor, saved, context);

            assertTrue(editor.bound());
            assertFalse(editor.active());
            assertEquals(1, opened.get()); // metadata/validation connection

            workspace.ensureActive(editor);
            assertTrue(editor.active());
            assertEquals(1, limiter.activeCount());
            assertEquals(2, opened.get());

            workspace.suspendIdle(Long.MAX_VALUE);
            assertTrue(editor.bound());
            assertFalse(editor.active());
            assertEquals(0, limiter.activeCount());

            workspace.ensureActive(editor);
            assertTrue(editor.active());
            assertEquals(1, limiter.activeCount());
            assertEquals(4, opened.get()); // editor session and lazily reopened metadata session
        } finally {
            workspace.close();
        }
        assertEquals(0, limiter.activeCount());
    }

    private static DatabaseProvider provider(final AtomicInteger opened) {
        final MetadataAdapter metadata = new MetadataAdapter() {
            @Override public List<String> listCatalogs(DatabaseSession session) { return Collections.emptyList(); }
            @Override public List<DatabaseObject> listObjects(DatabaseSession session, String catalog,
                                                               DatabaseObjectType type) { return Collections.emptyList(); }
            @Override public List<ColumnInfo> listColumns(DatabaseSession session, String catalog,
                                                          String schema, String objectName) { return Collections.emptyList(); }
            @Override public String definition(DatabaseSession session, DatabaseObject object) { return ""; }
        };
        final SqlDialect dialect = new SqlDialect() {
            @Override public String id() { return "fake"; }
            @Override public String quoteIdentifier(String identifier) { return identifier; }
            @Override public Set<String> keywords() { return Collections.emptySet(); }
            @Override public List<SqlStatement> split(String script) { return Collections.emptyList(); }
            @Override public Optional<SqlStatement> currentStatement(String script, int cursorOffset) {
                return Optional.empty();
            }
            @Override public StatementType classify(String sql) { return StatementType.QUERY; }
            @Override public String format(String sql) { return sql; }
        };
        return new DatabaseProvider() {
            @Override public String id() { return "fake"; }
            @Override public String displayName() { return "Fake"; }
            @Override public List<ConnectionField> connectionFields() { return Collections.emptyList(); }
            @Override public DatabaseCapabilities capabilities() { return DatabaseCapabilities.of(); }
            @Override public ConnectionAdapter connections() {
                return new ConnectionAdapter() {
                    @Override public ConnectionTestResult test(ConnectionProfile profile, char[] password) {
                        return new ConnectionTestResult(true, "ok", "fake", Duration.ZERO);
                    }
                    @Override public DatabaseSession connect(ConnectionProfile profile, char[] password) {
                        opened.incrementAndGet();
                        return new DatabaseSession() {
                            private boolean closed;
                            @Override public Connection jdbcConnection() { return null; }
                            @Override public String currentCatalog() { return ""; }
                            @Override public boolean isClosed() { return closed; }
                            @Override public void close() throws SQLException { closed = true; }
                        };
                    }
                };
            }
            @Override public MetadataAdapter metadata() { return metadata; }
            @Override public SqlDialect dialect() { return dialect; }
        };
    }
}
