package com.dbstudio.mysql;

import com.dbstudio.spi.StatementType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
