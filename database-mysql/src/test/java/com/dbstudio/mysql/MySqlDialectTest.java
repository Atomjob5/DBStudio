package com.dbstudio.mysql;

import com.dbstudio.spi.StatementType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlDialectTest {
    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    void resolvesPhysicalNamesForSimpleSelectAliases() {
        assertEquals(Arrays.asList("id", "amount", ""), new MySqlDialect().resultColumnNames(
                "SELECT t.id AS order_id, amount, amount + 1 AS calculated FROM orders t"));
    }

    @Test
    void ignoresSemicolonsInsideStringsAndComments() {
        java.util.List<com.dbstudio.spi.SqlStatement> statements = dialect.split("SELECT ';' AS value; -- keep ; here\nSELECT 2;");

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).text().contains("';'"));
        assertEquals(StatementType.QUERY, statements.get(1).type());
    }

    @Test
    void supportsDelimiterDirectivesForRoutines() {
        String script = "DELIMITER $$\nCREATE PROCEDURE p()\nBEGIN\n  SELECT 1;\nEND$$\n"
                + "DELIMITER ;\nCALL p();\n";

        java.util.List<com.dbstudio.spi.SqlStatement> statements = dialect.split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).text().startsWith("CREATE PROCEDURE"));
        assertEquals("CALL p()", statements.get(1).text());
    }

    @Test
    void locatesCurrentStatementByCursor() {
        String script = "SELECT 1;\nSELECT 2;";
        com.dbstudio.spi.SqlStatement statement = dialect.currentStatement(script, script.indexOf('2')).get();
        assertEquals("SELECT 2", statement.text());
    }

    @Test
    void compactsSqlWithoutChangingStringsOrDroppingComments() {
        String sql = "select /*+ MAX_EXECUTION_TIME(1000) */ id, 'a  b' as value /* keep block */\n"
                + "from demo -- keep line\nwhere id = 1;";

        String compact = dialect.compact(sql);

        assertTrue(compact.contains("/*+ MAX_EXECUTION_TIME(1000) */"));
        assertTrue(compact.contains("/* keep block */"));
        assertTrue(compact.contains("-- keep line"));
        assertTrue(compact.contains("'a  b'"));
        assertFalse(dialect.compact("select id,\nname\nfrom demo\nwhere id = 1").contains("\n"));
        assertEquals("", dialect.compact(null));
        assertEquals("   ", dialect.compact("   "));
    }

    @Test
    void recognizesOnlySafeSingleTableMutationSources() {
        com.dbstudio.spi.ResultMutationSource source = dialect.resultMutationSource(
                "SELECT o.id AS order_id, o.amount FROM `eastwealthcrawler`.`orders` o WHERE o.id > 0").get();
        assertEquals("eastwealthcrawler", source.catalog());
        assertEquals("orders", source.table());
        assertFalse(source.editableForUpdate());
        assertTrue(dialect.resultMutationSource(
                "SELECT o.id, o.amount FROM orders o FOR UPDATE").get().editableForUpdate());
        assertTrue(dialect.resultMutationSource(
                "SELECT o.id FROM orders o FOR UPDATE NOWAIT").get().editableForUpdate());
        assertTrue(dialect.resultMutationSource(
                "SELECT o.id FROM orders o FOR UPDATE SKIP LOCKED").get().editableForUpdate());
        assertFalse(dialect.resultMutationSource(
                "SELECT id FROM orders FOR SHARE").get().editableForUpdate());
        assertFalse(dialect.resultMutationSource(
                "SELECT id FROM orders /* FOR UPDATE */").get().editableForUpdate());
        assertFalse(dialect.resultMutationSource("SELECT a.id FROM orders a JOIN items b ON b.order_id=a.id").isPresent());
        assertFalse(dialect.resultMutationSource("SELECT id, amount + 1 FROM orders").isPresent());
        assertFalse(dialect.resultMutationSource("WITH data AS (SELECT * FROM orders) SELECT * FROM data").isPresent());
        assertFalse(dialect.resultMutationSource("SELECT id FROM orders UNION SELECT id FROM archive_orders").isPresent());
    }
}
