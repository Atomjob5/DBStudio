package com.dbstudio.oracle.common;

import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.ResultMutationSource;
import com.dbstudio.spi.SqlStatement;
import com.dbstudio.spi.SqlDiagnostic;
import com.dbstudio.spi.SqlDmlRiskAnalyzer;
import com.dbstudio.spi.StatementType;
import com.dbstudio.spi.TransactionEffect;
import java.sql.Types;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OracleDialectTest {
    private final OracleDialect dialect = new OracleDialect();

    @Test void splitsSqlAndPlSqlWithoutSendingSlash() {
        String script = "SELECT 1 FROM dual;\nCREATE OR REPLACE PROCEDURE demo AS\n"
                + "BEGIN\n  INSERT INTO audit_log(id) VALUES (1);\n  COMMIT;\nEND;\n/\nSELECT 2 FROM dual;";
        List<SqlStatement> statements = dialect.split(script);
        assertEquals(3, statements.size());
        assertEquals("SELECT 1 FROM dual", statements.get(0).text());
        assertTrue(statements.get(1).text().startsWith("CREATE OR REPLACE PROCEDURE"));
        assertFalse(statements.get(1).text().endsWith("/"));
        assertEquals("SELECT 2 FROM dual", statements.get(2).text());
    }

    @Test void keepsSemicolonsAndApostrophesInsideAlternativeQuotes() {
        String script = "SELECT q'[it's; still one value]' FROM dual;\nSELECT 2 FROM dual;";
        List<SqlStatement> statements = dialect.split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).text().contains("it's; still one value"));
        assertEquals("SELECT 2 FROM dual", statements.get(1).text());
        assertTrue(dialect.syntaxDiagnostics(script).isEmpty());
    }

    @Test void ignoresCompleteCommentOnlyFragmentsInDiagnosticsAndExecution() {
        String script = "SELECT 1 FROM dual;\n-- asd\nSELECT 2 FROM dual; /* test */";

        assertEquals(2, dialect.split(script).size());
        assertTrue(dialect.syntaxDiagnostics(script).isEmpty());
        assertTrue(dialect.currentStatement(script, script.indexOf("asd")).isEmpty());
        assertTrue(dialect.currentStatement(script, script.indexOf("SELECT 2")).isPresent());
        assertTrue(dialect.currentStatement(script, script.indexOf("test")).get().text().contains("SELECT 2"));
        String inlineComment = "SELECT 1 /* 中文😀 */ FROM dual";
        assertTrue(dialect.currentStatement(inlineComment, inlineComment.indexOf("中文")).isPresent());
        String leadingComment = "/* leading */\nSELECT 1 FROM dual";
        assertEquals(1, dialect.split(leadingComment).size());
        assertTrue(dialect.syntaxDiagnostics(leadingComment).isEmpty());
        assertTrue(dialect.split(leadingComment).get(0).text().startsWith("/* leading */"));
        assertTrue(dialect.currentStatement(leadingComment, leadingComment.indexOf("leading")).isEmpty());
        assertTrue(dialect.split("-- only\n/* only */").isEmpty());
        assertTrue(dialect.syntaxDiagnostics("-- only\n/* only */").isEmpty());
    }

    @Test void selectsOnlyTheOracleSqlAssociatedWithTheExecutableCursorLine() {
        String hint = "select /*+parallel(8)*/ * from CBSAC.APP_CONFIG a\nwhere a.ID=1 ;";
        assertTrue(dialect.currentStatement(hint, hint.indexOf("parallel") + 7).get().text()
                .startsWith("select /*+parallel(8)*/"));

        String trailing = "select * from CBSAC.APP_CONFIG a\nleft join CBSLN.APP_CONFIG b on a.ID=b.ID\n"
                + "where a.ID=1 ; /* test */";
        assertTrue(dialect.currentStatement(trailing, trailing.indexOf("test") + 2).get().text()
                .startsWith("select * from CBSAC.APP_CONFIG"));

        String between = "SELECT 1 FROM dual; /* between */ SELECT 2 FROM dual;";
        assertEquals("SELECT 1 FROM dual", dialect.currentStatement(
                between, between.indexOf("between") + 2).get().text());
        String commentInsideStatement = "SELECT *\n/* comment only line */\nFROM APP_CONFIG";
        assertTrue(dialect.currentStatement(commentInsideStatement,
                commentInsideStatement.indexOf("comment")).isEmpty());
        String blankBetweenStatements = "SELECT 1 FROM dual;\n\nSELECT 2 FROM dual;";
        assertTrue(dialect.currentStatement(blankBetweenStatements,
                blankBetweenStatements.indexOf("\n\n") + 1).isEmpty());
        String quoted = "SELECT q'[-- 中文😀]' FROM dual";
        assertTrue(dialect.currentStatement(quoted, quoted.indexOf("中文")).isPresent());
    }

    @Test void keepsOracleCommentMarkersInsideQuotedValuesAndSkipsOnlyHints() {
        assertEquals(1, dialect.split("SELECT '-- note', '/* text */', \"-- identifier\" FROM dual;").size());
        assertEquals(1, dialect.split("SELECT /*+ INDEX(t) */ 1 FROM dual").size());
        assertTrue(dialect.split("/*+ standalone hint */\n-- 注释😀").isEmpty());
    }

    @Test void usesOracleQualificationPreviewAndLiterals() {
        DatabaseObject table = new DatabaseObject(DatabaseObjectType.TABLE, "", "SALES", "Order",
                "", Collections.<String, String>emptyMap());
        assertEquals("\"SALES\".\"Order\"", dialect.qualifiedName("", "SALES", "Order"));
        assertTrue(dialect.previewQuery(table, 1000).contains("FETCH FIRST 1000 ROWS ONLY"));
        assertEquals("HEXTORAW('0aff')", dialect.sqlLiteral("0x0aff", Types.VARBINARY));
        assertEquals("DATE '2026-07-22'", dialect.sqlLiteral("2026-07-22", Types.DATE));
    }

    @Test void classifiesTransactionEffects() {
        assertEquals(TransactionEffect.IMPLICIT_COMMIT, dialect.transactionEffect(
                new SqlStatement("CREATE TABLE x(id NUMBER)", 0, 25, StatementType.DDL), false));
        assertEquals(TransactionEffect.DIRTY, dialect.transactionEffect(
                new SqlStatement("SELECT * FROM x FOR UPDATE", 0, 26, StatementType.QUERY), true));
        assertEquals(TransactionEffect.NONE, dialect.transactionEffect(
                new SqlStatement("SELECT * FROM x", 0, 15, StatementType.QUERY), true));
    }

    @Test void plansOffsetFetchPagesAndFallsBackForOracleRestrictions() {
        com.dbstudio.spi.SqlDialect.PagePlan page = dialect.pageQuery(
                "/* keep */ SELECT id, name FROM sales.orders ORDER BY id FETCH FIRST 10 ROWS ONLY", 3, 2);
        assertTrue(page.nativePaging());
        String rewritten = page.sql().toUpperCase();
        assertTrue(rewritten.contains("OFFSET 3 ROWS"));
        assertTrue(rewritten.contains("FETCH"));
        assertTrue(dialect.pageQuery("SELECT id, id FROM sales.orders", 0, 2).nativePaging() == false);
        assertFalse(dialect.pageQuery("SELECT id FROM sales.orders FOR UPDATE", 0, 2).nativePaging());
        assertFalse(dialect.pageQuery("SELECT seq.NEXTVAL FROM dual", 0, 2).nativePaging());
        assertTrue(dialect.pageQuery("SELECT id FROM sales.orders FETCH FIRST 2 ROWS ONLY", 2, 2).empty());
    }

    @Test void identifiesUpdateAndDeleteWithoutTopLevelWhereAsRisky() {
        assertTrue(dialect.requiresWhereClauseConfirmation(statement("UPDATE orders SET status = 'CLOSED'")));
        assertTrue(dialect.requiresWhereClauseConfirmation(statement("DELETE FROM orders")));
        assertTrue(dialect.requiresWhereClauseConfirmation(statement(
                "WITH source AS (SELECT 1 id FROM dual) UPDATE orders SET status = 'CLOSED'")));
        assertFalse(dialect.requiresWhereClauseConfirmation(statement(
                "UPDATE orders SET note = q'[WHERE]' WHERE id = 1")));
        assertFalse(dialect.requiresWhereClauseConfirmation(statement("DELETE FROM orders WHERE id = 1")));
        assertFalse(dialect.requiresWhereClauseConfirmation(statement(
                "MERGE INTO orders d USING source s ON (d.id = s.id) WHEN MATCHED THEN UPDATE SET d.status = 'CLOSED'")));
    }

    @Test void diagnosesOracleSyntaxAndMultiStatementOffsets() {
        for (String valid : java.util.Arrays.asList(
                "SELECT \"Id\", q'[中文 ( text ]' FROM \"Orders\" FETCH FIRST 10 ROWS ONLY",
                "MERGE INTO target t USING source s ON (t.id=s.id) WHEN MATCHED THEN UPDATE SET t.name=s.name",
                "BEGIN NULL; END;\n/")) {
            assertTrue(dialect.syntaxDiagnostics(valid).isEmpty(), valid);
        }
        String script = "SELECT '中文😀' FROM dual;\nSELECT ( FROM dual";
        List<SqlDiagnostic> diagnostics = dialect.syntaxDiagnostics(script);
        assertEquals(1, diagnostics.size());
        assertEquals("SQL_SYNTAX_ERROR", diagnostics.get(0).code());
        assertTrue(diagnostics.get(0).startOffset() >= script.indexOf("SELECT ("));
        assertTrue(diagnostics.get(0).endOffset() <= script.length());
        assertFalse(dialect.syntaxDiagnostics("SELECT ( FROM SECRET_ORDERS").get(0).message()
                .contains("SECRET_ORDERS"));
    }

    @Test void locatesRiskyOracleKeywordAfterCteInUtf16Units() {
        String sql = "/* 😀 */ WITH source AS (SELECT 1 FROM dual WHERE 1=1) DELETE FROM orders";
        assertEquals(sql.indexOf("DELETE"), SqlDmlRiskAnalyzer.riskyDmlKeywordOffset(sql));
    }

    @Test void compactsOracleSqlWithoutChangingStringsOrDroppingComments() {
        String sql = "select /*+ index(o orders_pk) */ o.id, 'a  b' as value /* keep block */\n"
                + "from orders o -- keep line\nwhere o.id = 1";

        String compact = dialect.compact(sql);

        assertTrue(compact.contains("/*+ index(o orders_pk) */"));
        assertTrue(compact.contains("/* keep block */"));
        assertTrue(compact.contains("-- keep line"));
        assertTrue(compact.contains("'a  b'"));
        assertFalse(dialect.compact("select id,\nname\nfrom orders\nwhere id = 1").contains("\n"));
    }

    @Test void safelyResolvesSingleTableMutationSource() {
        ResultMutationSource source = dialect.resultMutationSource(
                "SELECT a.id, a.name FROM sales.orders a").orElseThrow(AssertionError::new);
        assertEquals("SALES", source.schema());
        assertEquals("ORDERS", source.table());
        assertFalse(source.editableForUpdate());
        ResultMutationSource quoted = dialect.resultMutationSource(
                "SELECT a.id FROM \"sales\".\"orders\" a FOR UPDATE").orElseThrow(AssertionError::new);
        assertEquals("sales", quoted.schema());
        assertEquals("orders", quoted.table());
        assertTrue(dialect.resultMutationSource(
                "SELECT a.id, a.name FROM sales.orders a FOR UPDATE").get().editableForUpdate());
        assertTrue(dialect.resultMutationSource(
                "SELECT a.id, a.name FROM sales.orders a FOR UPDATE OF a.name NOWAIT")
                .get().editableForUpdate());
        assertTrue(dialect.resultMutationSource(
                "SELECT a.id FROM sales.orders a FOR UPDATE SKIP LOCKED").get().editableForUpdate());
        assertFalse(dialect.resultMutationSource(
                "SELECT a.id FROM sales.orders a /* FOR UPDATE */").get().editableForUpdate());
        assertFalse(dialect.resultMutationSource(
                "SELECT 'FOR UPDATE' AS value FROM sales.orders").get().editableForUpdate());
        assertFalse(dialect.resultMutationSource("SELECT a.id FROM a JOIN b ON b.id=a.id").isPresent());
        assertFalse(dialect.resultMutationSource(
                "WITH data AS (SELECT id FROM sales.orders) SELECT id FROM data FOR UPDATE").isPresent());
        assertFalse(dialect.resultMutationSource(
                "SELECT id FROM sales.orders UNION SELECT id FROM sales.archive_orders").isPresent());
        assertTrue(dialect.resultMutationSource(
                "SELECT id, amount + 1 FROM sales.orders FOR UPDATE").isPresent());
        assertEquals("DATABASE_LINK_NOT_SUPPORTED", dialect.resultEditPlan(
                "SELECT id FROM sales.orders@remote FOR UPDATE").reasonCode());
        assertEquals("SET_QUERY_NOT_SUPPORTED", dialect.resultEditPlan(
                "SELECT id FROM sales.orders UNION SELECT id FROM sales.archive_orders").reasonCode());
        String unqualifiedSql = "SELECT * FROM APP_CONFIG a WHERE a.id = 1 FOR UPDATE";
        ResultMutationSource unqualified = dialect.resultMutationSource(unqualifiedSql)
                .orElseThrow(AssertionError::new);
        assertEquals("", unqualified.schema());
        assertEquals("a", dialect.resultMutationQualifier(unqualifiedSql, unqualified));
        assertEquals("APP_CONFIG", dialect.resultMutationQualifier(
                "SELECT * FROM APP_CONFIG FOR UPDATE", dialect.resultMutationSource(
                        "SELECT * FROM APP_CONFIG FOR UPDATE").orElseThrow(AssertionError::new)));
        String unqualifiedRewrite = dialect.appendResultLocatorColumns(
                unqualifiedSql, Collections.singletonList("ROWIDTOCHAR(a.ROWID)"),
                Collections.singletonList("DBSTUDIO_LOCATOR_0"));
        assertTrue(unqualifiedRewrite.contains("a.*"));
        assertTrue(unqualifiedRewrite.contains("ROWIDTOCHAR(a.ROWID)"));
        assertFalse(unqualifiedRewrite.matches("(?s).*SELECT\\s+\\*,.*"));
        String rewritten = dialect.appendResultLocatorColumns(
                "SELECT o.name AS label FROM sales.orders o ORDER BY o.id FETCH FIRST 10 ROWS ONLY FOR UPDATE",
                Collections.singletonList("ROWIDTOCHAR(o.ROWID)"),
                Collections.singletonList("DBSTUDIO_LOCATOR_0"));
        assertTrue(rewritten.contains("DBSTUDIO_LOCATOR_0"));
        assertTrue(rewritten.toUpperCase().contains("FOR UPDATE"));
        String refresh = dialect.appendResultLocatorPredicate(
                "SELECT o.id, o.amount * 2 doubled FROM sales.orders o WHERE o.amount > 10 "
                        + "FETCH FIRST 5 ROWS ONLY FOR UPDATE",
                Collections.singletonList("ROWID = CHARTOROWID(?)"));
        assertTrue(refresh.toUpperCase().contains("CHARTOROWID(?)"));
        assertTrue(refresh.toUpperCase().contains("AMOUNT > 10"));
        assertFalse(refresh.toUpperCase().contains("FETCH FIRST"));
    }

    private SqlStatement statement(String sql) {
        return new SqlStatement(sql, 0, sql.length(), dialect.classify(sql));
    }
}
