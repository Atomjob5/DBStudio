package com.dbstudio.oceanbase.oracle;

import com.dbstudio.spi.SqlDiagnostic;
import com.dbstudio.spi.SqlDialect;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OceanBaseOracleDialectDiagnosticsTest {
    private final SqlDialect dialect = new OceanBaseOracleDatabaseProvider().dialect();

    @Test void retainsProviderIdentityWhileInheritingOracleDiagnostics() {
        assertEquals("oceanbase-oracle", dialect.id());
        assertTrue(dialect.syntaxDiagnostics(
                "SELECT /*+ PARALLEL(2) */ s.ID FROM APP.ORDERS s FETCH FIRST 1 ROWS ONLY").isEmpty());
        assertTrue(dialect.syntaxDiagnostics(
                "MERGE INTO APP.TARGET t USING APP.SOURCE s ON (t.ID=s.ID) "
                        + "WHEN MATCHED THEN UPDATE SET t.VALUE=s.VALUE").isEmpty());
        assertTrue(dialect.syntaxDiagnostics("SELECT q'[it's; valid]' FROM dual").isEmpty());

        String invalid = "SELECT '中文😀' FROM dual; SELECT ( FROM APP.ORDERS";
        List<SqlDiagnostic> diagnostics = dialect.syntaxDiagnostics(invalid);
        assertEquals(1, diagnostics.size());
        assertEquals("SQL_SYNTAX_ERROR", diagnostics.get(0).code());
        assertTrue(diagnostics.get(0).startOffset() >= invalid.indexOf("SELECT ("));
    }

    @Test void appliesOracleRiskRulesDirectly() {
        assertTrue(dialect.requiresWhereClauseConfirmation(dialect.split(
                "UPDATE APP.ORDERS SET STATUS='CLOSED'").get(0)));
        assertFalse(dialect.requiresWhereClauseConfirmation(dialect.split(
                "DELETE FROM APP.ORDERS WHERE ID=1").get(0)));
    }

    @Test void ignoresTrailingCommentOnlyFragmentsWithItsOwnProviderContract() {
        String script = "SELECT 1 FROM dual;\n-- asd\nSELECT 2 FROM dual; /* test */";

        assertEquals("oceanbase-oracle", dialect.id());
        assertEquals(2, dialect.split(script).size());
        assertTrue(dialect.syntaxDiagnostics(script).isEmpty());
        assertTrue(dialect.currentStatement(script, script.indexOf("asd")).isEmpty());
        assertTrue(dialect.currentStatement(script, script.indexOf("test")).get().text().contains("SELECT 2"));
        String hint = "SELECT /*+ PARALLEL(8) */ * FROM CBSAC.APP_CONFIG a\nWHERE a.ID=1;";
        assertTrue(dialect.currentStatement(hint, hint.indexOf("PARALLEL") + 4).isPresent());
        assertTrue(dialect.split("-- only\n/* only */").isEmpty());
    }
}
