package com.dbstudio.desktop.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertNull(resolved.mutationTarget());
    }

    private static List<ResultColumn> columns(String catalog, String schema, String table) {
        return Arrays.asList(
                new ResultColumn("ID", "ID", catalog, schema, table,
                        "NUMBER", "", Types.NUMERIC, "\"ID\""),
                new ResultColumn("DISPLAY_NAME", "DISPLAY_NAME", catalog, schema, table,
                        "VARCHAR2", "", Types.VARCHAR, "\"DISPLAY_NAME\""));
    }

    private static DatabaseSession session() {
        return new DatabaseSession() {
            @Override public Connection jdbcConnection() { return null; }
            @Override public String currentCatalog() { return ""; }
            @Override public void close() { }
        };
    }

    private static SqlDialect dialect() {
        return new SqlDialect() {
            @Override public String id() { return "oracle-test"; }
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
                return Optional.of(new ResultMutationSource("", "CBSAC", "APP_CONFIG", true));
            }
        };
    }

    private static final class CapturingMetadata implements MetadataAdapter {
        private String catalog;
        private String schema;
        private String table;

        @Override public List<ColumnInfo> listColumns(DatabaseSession session, String catalog,
                                                      String schema, String objectName) {
            return Collections.emptyList();
        }

        @Override public boolean isBaseTable(DatabaseSession session, String catalog,
                                             String schema, String objectName) {
            this.catalog = catalog;
            this.schema = schema;
            this.table = objectName;
            return true;
        }

        @Override public List<UniqueKeyInfo> listUniqueKeys(DatabaseSession session, String catalog,
                                                            String schema, String objectName) {
            return Collections.singletonList(
                    new UniqueKeyInfo("PK_APP_CONFIG", true, Collections.singletonList("ID")));
        }

        @Override public String definition(DatabaseSession session, DatabaseObject object) {
            return "";
        }
    }
}
