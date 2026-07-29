package com.dbstudio.oracle.common;

import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.ResultMutationSource;
import com.dbstudio.spi.SqlStatement;
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
        assertEquals("SALES", source.schema().toUpperCase());
        assertEquals("ORDERS", source.table().toUpperCase());
        assertFalse(dialect.resultMutationSource("SELECT a.id FROM a JOIN b ON b.id=a.id").isPresent());
    }
}
