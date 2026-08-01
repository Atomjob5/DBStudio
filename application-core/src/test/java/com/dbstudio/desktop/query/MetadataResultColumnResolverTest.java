package com.dbstudio.desktop.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.ResultMutationSource;
import com.dbstudio.spi.SqlDialect;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.StatementType;
import com.dbstudio.spi.UniqueKeyInfo;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetadataResultColumnResolverTest {
    @Test
    void resolvesForUpdateTargetWhenOracleJdbcOmitsPhysicalTableMetadata() {
        CapturingMetadata metadata = new CapturingMetadata();
        MetadataResultColumnResolver resolver = new MetadataResultColumnResolver(
                metadata, session(), dialect());

        ResolvedResultMetadata resolved = resolver.resolve(
                "select * from CBSAC.APP_CONFIG where id = 1 for update",
                columns("", "", ""));

        ResultMutationTarget target = resolved.mutationTarget();
        assertNotNull(target);
        assertTrue(target.editableForUpdate());
        assertEquals("\"CBSAC\".\"APP_CONFIG\"", target.qualifiedName());
        assertEquals(Arrays.asList(0), target.uniqueKeys().get(0).resultColumnIndices());
        assertEquals("", metadata.catalog);
        assertEquals("CBSAC", metadata.schema);
        assertEquals("APP_CONFIG", metadata.table);
    }

    @Test
    void rejectsConflictingJdbcTableMetadata() {
        MetadataResultColumnResolver resolver = new MetadataResultColumnResolver(
                new CapturingMetadata(), session(), dialect());

        ResolvedResultMetadata resolved = resolver.resolve(
                "select * from CBSAC.APP_CONFIG where id = 1 for update",
                columns("", "CBSAC", "OTHER_TABLE"));

        assertNotNull(resolved.mutationTarget());
        assertEquals("AMBIGUOUS_PROJECTION", resolved.mutationTarget().reasonCode());
    }

    @Test
    void resolvesUnqualifiedOracleCompatibleTablesFromTheCurrentSessionSchema() {
        for (String dialectId : Arrays.asList("oracle-test", "oceanbase-oracle-test")) {
            CapturingMetadata metadata = new CapturingMetadata();
            MetadataResultColumnResolver resolver = new MetadataResultColumnResolver(
                    metadata, session("CBSAC"), dialect(dialectId, "", "a"));

            PreparedResultQuery prepared = resolver.prepare(
                    "select * from APP_CONFIG a where a.id = 1 for update");

            assertEquals("CBSAC", prepared.mutationSource().schema());
            assertEquals("APP_CONFIG", prepared.mutationSource().table());
            assertTrue(prepared.executionSql().contains("ROWIDTOCHAR(a.ROWID)"));
            assertEquals("CBSAC", metadata.schema);
            ResolvedResultMetadata resolved = resolver.resolve(prepared, columns("", "", ""));
            assertTrue(resolved.mutationTarget().editableForUpdate());
            assertEquals("ROWID", resolved.mutationTarget().locator().kind());
            assertEquals("\"CBSAC\".\"APP_CONFIG\"", resolved.mutationTarget().qualifiedName());
        }
    }

    @Test
    void explicitSchemaWinsOverSessionAndJdbcResultMetadata() {
        CapturingMetadata metadata = new CapturingMetadata();
        MetadataResultColumnResolver resolver = new MetadataResultColumnResolver(
                metadata, session("CURRENT_OWNER"), dialect("oracle-test", "EXPLICIT_OWNER", "a"));

        PreparedResultQuery prepared = resolver.prepare(
                "select * from EXPLICIT_OWNER.APP_CONFIG a for update");
        ResolvedResultMetadata resolved = resolver.resolve(
                prepared, columns("", "CURRENT_OWNER", "APP_CONFIG"));

        assertEquals("EXPLICIT_OWNER", prepared.mutationSource().schema());
        assertEquals("EXPLICIT_OWNER", metadata.schema);
        assertEquals("AMBIGUOUS_PROJECTION", resolved.mutationTarget().reasonCode());
        assertEquals("\"EXPLICIT_OWNER\".\"APP_CONFIG\"", resolved.mutationTarget().qualifiedName());
    }

    @Test
    void currentSessionSchemaIsNotOverwrittenByJdbcResultMetadata() {
        MetadataResultColumnResolver resolver = new MetadataResultColumnResolver(
                new CapturingMetadata(), session("CURRENT_OWNER"), dialect("oracle-test", "", "a"));

        PreparedResultQuery prepared = resolver.prepare("select * from APP_CONFIG a for update");
        ResolvedResultMetadata resolved = resolver.resolve(
                prepared, columns("", "OTHER_OWNER", "APP_CONFIG"));

        assertEquals("CURRENT_OWNER", prepared.mutationSource().schema());
        assertEquals("AMBIGUOUS_PROJECTION", resolved.mutationTarget().reasonCode());
        assertEquals("\"CURRENT_OWNER\".\"APP_CONFIG\"", resolved.mutationTarget().qualifiedName());
    }

    @Test
    void oracleRowIdEditingDoesNotRequireAProjectedPrimaryKey() {
        CapturingMetadata metadata = new CapturingMetadata();
        metadata.hasSafeKey = false;
        MetadataResultColumnResolver resolver = new MetadataResultColumnResolver(
                metadata, session("CBSAC"), dialect("oracle-test", "", "a"));

        PreparedResultQuery prepared = resolver.prepare("select * from APP_CONFIG a for update");
        ResultMutationTarget target = resolver.resolve(prepared, columns("", "", "")).mutationTarget();

        assertTrue(target.uniqueKeys().isEmpty());
        assertEquals("ROWID", target.locator().kind());
        assertTrue(target.editableForUpdate());
    }

    @Test
    void reportsUnresolvedOwnerBeforeCheckingTheDataDictionary() {
        CapturingMetadata metadata = new CapturingMetadata();
        MetadataResultColumnResolver resolver = new MetadataResultColumnResolver(
                metadata, session(""), dialect("oracle-test", "", "a"));

        PreparedResultQuery prepared = resolver.prepare("select * from APP_CONFIG a for update");
        ResolvedResultMetadata resolved = resolver.resolve(prepared, columns("", "", ""));

        assertNull(prepared.locator());
        assertEquals(0, metadata.baseTableChecks);
        assertEquals("TARGET_OWNER_UNRESOLVED", resolved.mutationTarget().reasonCode());
    }

    @Test
    void distinguishesNonBaseTablesFromMetadataFailures() {
        CapturingMetadata viewMetadata = new CapturingMetadata();
        viewMetadata.baseTable = false;
        MetadataResultColumnResolver viewResolver = new MetadataResultColumnResolver(
                viewMetadata, session("CBSAC"), dialect("oracle-test", "", "a"));
        PreparedResultQuery viewQuery = viewResolver.prepare("select * from APP_CONFIG a for update");
        assertEquals("VIEW_NOT_SUPPORTED",
                viewResolver.resolve(viewQuery, columns("", "", "")).mutationTarget().reasonCode());

        CapturingMetadata unavailableMetadata = new CapturingMetadata();
        unavailableMetadata.failure = new SQLException("dictionary unavailable");
        MetadataResultColumnResolver unavailableResolver = new MetadataResultColumnResolver(
                unavailableMetadata, session("CBSAC"), dialect("oracle-test", "", "a"));
        PreparedResultQuery unavailableQuery = unavailableResolver.prepare(
                "select * from APP_CONFIG a for update");
        ResultMutationTarget unavailable = unavailableResolver.resolve(
                unavailableQuery, columns("", "", "")).mutationTarget();
        assertFalse(unavailable.editableForUpdate());
        assertEquals("METADATA_UNAVAILABLE", unavailable.reasonCode());
    }

    private static List<ResultColumn> columns(String catalog, String schema, String table) {
        return Arrays.asList(
                new ResultColumn("ID", "ID", catalog, schema, table,
                        "NUMBER", "", Types.NUMERIC, "\"ID\""),
                new ResultColumn("DISPLAY_NAME", "DISPLAY_NAME", catalog, schema, table,
                        "VARCHAR2", "", Types.VARCHAR, "\"DISPLAY_NAME\""));
    }

    private static DatabaseSession session() {
        return session("");
    }

    private static DatabaseSession session(final String schema) {
        return new DatabaseSession() {
            @Override public Connection jdbcConnection() { return null; }
            @Override public String currentCatalog() { return ""; }
            @Override public String currentSchema() { return schema; }
            @Override public void close() { }
        };
    }

    private static SqlDialect dialect() {
        return dialect("oracle-test", "CBSAC", "");
    }

    private static SqlDialect dialect(final String dialectId, final String schema, final String alias) {
        return new SqlDialect() {
            @Override public String id() { return dialectId; }
            @Override public String quoteIdentifier(String identifier) {
                return "\"" + identifier + "\"";
            }
            @Override public Set<String> keywords() { return Collections.emptySet(); }
            @Override public List<SqlStatement> split(String script) { return Collections.emptyList(); }
            @Override public Optional<SqlStatement> currentStatement(String script, int cursorOffset) {
                return Optional.empty();
            }
            @Override public StatementType classify(String sql) { return StatementType.QUERY; }
            @Override public String format(String sql) { return sql; }
            @Override public List<String> resultColumnNames(String sql) {
                return Arrays.asList("ID", "DISPLAY_NAME");
            }
            @Override public Optional<ResultMutationSource> resultMutationSource(String sql) {
                return Optional.of(new ResultMutationSource("", schema, "APP_CONFIG", alias, true));
            }
            @Override public String resultMutationQualifier(String sql, ResultMutationSource source) {
                return source.alias().isEmpty() ? source.table() : source.alias();
            }
            @Override public String appendResultLocatorColumns(String sql, List<String> expressions,
                                                                List<String> aliases) {
                return sql + " /* " + String.join(",", expressions) + " */";
            }
            @Override public String appendResultLocatorPredicate(String sql, List<String> predicates) {
                return sql + " /* " + String.join(" AND ", predicates) + " */";
            }
        };
    }

    private static final class CapturingMetadata implements MetadataAdapter {
        private String catalog;
        private String schema;
        private String table;
        private boolean baseTable = true;
        private boolean hasSafeKey = true;
        private SQLException failure;
        private int baseTableChecks;

        @Override public List<ColumnInfo> listColumns(DatabaseSession session, String catalog,
                                                      String schema, String objectName) {
            return Arrays.asList(
                    new ColumnInfo("ID", "NUMBER", 38, 0, false, "", true, 1),
                    new ColumnInfo("DISPLAY_NAME", "VARCHAR2", 200, 0, true, "", false, 2));
        }

        @Override public boolean isBaseTable(DatabaseSession session, String catalog,
                                             String schema, String objectName) throws SQLException {
            baseTableChecks++;
            if (failure != null) throw failure;
            this.catalog = catalog;
            this.schema = schema;
            this.table = objectName;
            return baseTable;
        }

        @Override public List<UniqueKeyInfo> listUniqueKeys(DatabaseSession session, String catalog,
                                                            String schema, String objectName) {
            return hasSafeKey ? Collections.singletonList(
                    new UniqueKeyInfo("PK_APP_CONFIG", true, Collections.singletonList("ID")))
                    : Collections.<UniqueKeyInfo>emptyList();
        }

        @Override public String definition(DatabaseSession session, DatabaseObject object) {
            return "";
        }
    }
}
