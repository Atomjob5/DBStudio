package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.desktop.DatabaseContext;
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
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceSessionLifecycleTest {
    @TempDir Path directory;

    @Test
    void scopesLargeValueDraftsAndCleansTheirTemporaryFiles() throws Exception {
        EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        Workspace workspace = new Workspace("lob-workspace", 100, 20, false,
                new ObjectMapper(), directory.resolve("temporary"), limiter);
        UUID execution = UUID.randomUUID();
        try {
            String token = workspace.storeLargeValueDraft("editor-1", execution, 2, 3,
                    new ByteArrayInputStream(new byte[] { 1, 2, 3, 4 }), 4);
            Path stored = workspace.requireLargeValueDraft(token, "editor-1", execution, 2, 3);
            assertTrue(Files.exists(stored));
            assertEquals(4, workspace.largeValueDraftSize(token));
            ApiException foreign = assertThrows(ApiException.class, () -> workspace.requireLargeValueDraft(
                    token, "editor-2", execution, 2, 3));
            assertEquals("RESULT_LOB_TOKEN_INVALID", foreign.getCode());
            assertThrows(ApiException.class, () -> workspace.storeLargeValueDraft("editor-1", execution,
                    2, 3, new ByteArrayInputStream(new byte[] { 1, 2, 3, 4, 5 }), 4));
            workspace.removeLargeValueDrafts("editor-1");
            assertFalse(Files.exists(stored));
        } finally {
            workspace.close();
        }
    }

    @Test
    void clonesLargeValueDraftsIntoIndependentTemporaryFiles() throws Exception {
        EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        Workspace workspace = new Workspace("lob-clone-workspace", 100, 20, false,
                new ObjectMapper(), directory.resolve("clone-temporary"), limiter);
        UUID execution = UUID.randomUUID();
        try {
            String sourceToken = workspace.storeLargeValueDraft("editor-1", execution, 0, 2,
                    new ByteArrayInputStream(new byte[] { 1, 2, 3, 4 }), 8);
            Path source = workspace.requireLargeValueDraft(sourceToken, "editor-1", execution, 0, 2);
            String cloneToken = workspace.cloneLargeValueDraft(sourceToken, "editor-1", execution, 0, 2, 8);
            Path clone = workspace.requireLargeValueDraft(cloneToken, "editor-1", execution, 0, 2);

            assertFalse(source.equals(clone));
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(clone));
            workspace.removeLargeValueDraft(sourceToken);
            assertFalse(Files.exists(source));
            assertTrue(Files.exists(clone));
            assertArrayEquals(new byte[] { 1, 2, 3, 4 }, Files.readAllBytes(clone));
        } finally {
            workspace.close();
        }
    }

    @Test
    void removesAnIncompleteStreamedLargeValueDraft() throws Exception {
        EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        Path temporary = directory.resolve("failed-clone-temporary");
        Workspace workspace = new Workspace("lob-failed-clone-workspace", 100, 20, false,
                new ObjectMapper(), temporary, limiter);
        UUID execution = UUID.randomUUID();
        try {
            assertThrows(IOException.class, () -> workspace.storeLargeValueDraft(
                    "editor-1", execution, 0, 2, output -> {
                        output.write(new byte[] { 1, 2, 3 });
                        throw new IOException("stream failed");
                    }, 8));
            assertTrue(Files.exists(temporary));
            try (java.util.stream.Stream<Path> files = Files.list(temporary)) {
                assertEquals(0L, files.count());
            }
        } finally {
            workspace.close();
        }
    }

    @Test
    void reusesIdleJdbcAndCreatesANewGenerationAfterDisconnect() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        DatabaseProvider provider = provider(opened);
        ConnectionProfile profile = new ConnectionProfile(
                UUID.randomUUID(), "fake", "测试链接", Collections.<String, String>emptyMap(), "test-secret");
        EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        limiter.setMaximum(10);
        DatabaseContext context = new DatabaseContext(provider, profile, new char[0]);
        WorkspaceJdbcPool pool = new WorkspaceJdbcPool("workspace:profile@revision", context, limiter, false);
        try {
            WorkspaceJdbcPool.Lease first = pool.borrow();
            pool.release(first);
            assertEquals(1, limiter.activeCount());
            assertEquals(1, opened.get());

            WorkspaceJdbcPool.Lease reused = pool.borrow();
            assertTrue(first.session() == reused.session());
            pool.release(reused);
            assertEquals(1, opened.get());

            pool.retireUnpinned();
            WorkspaceJdbcPool.Lease replacement = pool.borrow();
            assertEquals(2, opened.get());
            pool.release(replacement);
            pool.reap(Long.MAX_VALUE);
            assertEquals(0, limiter.activeCount());
        } finally {
            pool.close();
        }
        assertEquals(0, limiter.activeCount());
    }

    @Test
    void appliesAutoCommitToNewAndReusedEditorConnections() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        DatabaseProvider provider = provider(opened);
        ConnectionProfile profile = new ConnectionProfile(
                UUID.randomUUID(), "fake", "测试链接", Collections.<String, String>emptyMap(), "test-secret");
        EditorConnectionLimiter limiter = new EditorConnectionLimiter();
        limiter.setMaximum(10);
        DatabaseContext context = new DatabaseContext(provider, profile, new char[0]);
        WorkspaceJdbcPool pool = new WorkspaceJdbcPool("workspace:auto-commit", context, limiter, false);
        try {
            WorkspaceJdbcPool.Lease manual = pool.borrow();
            assertTrue(!manual.session().jdbcConnection().getAutoCommit());
            assertThrows(IllegalStateException.class, () -> pool.setAutoCommit(true));
            pool.release(manual);

            pool.setAutoCommit(true);
            assertEquals(0, limiter.activeCount());
            WorkspaceJdbcPool.Lease automatic = pool.borrow();
            assertTrue(automatic.session().jdbcConnection().getAutoCommit());
            pool.release(automatic);

            WorkspaceJdbcPool.Lease reused = pool.borrow();
            assertTrue(automatic.session() == reused.session());
            assertTrue(reused.session().jdbcConnection().getAutoCommit());
            pool.release(reused);
            assertEquals(2, opened.get());
        } finally {
            pool.close();
        }
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
                        final AtomicBoolean closed = new AtomicBoolean();
                        final AtomicBoolean autoCommit = new AtomicBoolean();
                        final Connection jdbc = (Connection) Proxy.newProxyInstance(
                                Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                                (proxy, method, args) -> {
                                    String name = method.getName();
                                    if ("isClosed".equals(name)) return closed.get();
                                    if ("isValid".equals(name)) return !closed.get();
                                    if ("getAutoCommit".equals(name)) return autoCommit.get();
                                    if ("setAutoCommit".equals(name)) {
                                        autoCommit.set((Boolean) args[0]);
                                        return null;
                                    }
                                    if ("rollback".equals(name) && autoCommit.get()) {
                                        throw new SQLException("rollback is not allowed while auto-commit is enabled");
                                    }
                                    if ("close".equals(name)) { closed.set(true); return null; }
                                    if (method.getReturnType() == boolean.class) return false;
                                    if (method.getReturnType() == int.class) return 0;
                                    return null;
                                });
                        return new DatabaseSession() {
                            @Override public Connection jdbcConnection() { return jdbc; }
                            @Override public String currentCatalog() { return ""; }
                            @Override public boolean isClosed() { return closed.get(); }
                            @Override public void close() throws SQLException { jdbc.close(); }
                        };
                    }
                };
            }
            @Override public MetadataAdapter metadata() { return metadata; }
            @Override public SqlDialect dialect() { return dialect; }
        };
    }
}
