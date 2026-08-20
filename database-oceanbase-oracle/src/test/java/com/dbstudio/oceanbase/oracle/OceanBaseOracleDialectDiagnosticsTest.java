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
}
