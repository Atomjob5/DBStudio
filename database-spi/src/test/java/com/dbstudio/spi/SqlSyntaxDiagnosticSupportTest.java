package com.dbstudio.spi;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SqlSyntaxDiagnosticSupportTest {
    @Test
    void convertsLineColumnsToUtf16OffsetsAndSanitizesSql() {
        String sql = "SELECT '中文😀', broken";
        int expected = sql.indexOf("broken");
        SqlStatement statement = new SqlStatement(sql, 0, sql.length(), StatementType.QUERY);

        List<SqlDiagnostic> diagnostics = SqlSyntaxDiagnosticSupport.analyze(sql,
                Collections.singletonList(statement), value -> {
                    throw new IllegalArgumentException("unexpected token\nsource SQL is: " + value
                            + ", line 1, column " + (expected + 1));
                });

        assertEquals(1, diagnostics.size());
        assertEquals(expected, diagnostics.get(0).startOffset());
        assertEquals(expected + 1, diagnostics.get(0).endOffset());
        assertFalse(diagnostics.get(0).message().contains("broken"));
    }

    @Test
    void offsetsAStatementLocalPositionIntoTheWholeScript() {
        String prefix = "SELECT '中文😀';\n";
        String statementSql = "SELECT broken";
        String script = prefix + statementSql;
        SqlStatement statement = new SqlStatement(statementSql, prefix.length(), script.length(), StatementType.QUERY);

        List<SqlDiagnostic> diagnostics = SqlSyntaxDiagnosticSupport.analyze(script,
                Collections.singletonList(statement), value -> {
                    throw new IllegalArgumentException("syntax error, pos 7");
                });

        assertEquals(script.indexOf("broken"), diagnostics.get(0).startOffset());
    }
}
