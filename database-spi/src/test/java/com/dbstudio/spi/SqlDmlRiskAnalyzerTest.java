package com.dbstudio.spi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SqlDmlRiskAnalyzerTest {
    @Test
    void detectsOnlyTopLevelForUpdateClauses() {
        assertTrue(SqlDmlRiskAnalyzer.hasTopLevelForUpdate("SELECT * FROM orders FOR UPDATE"));
        assertTrue(SqlDmlRiskAnalyzer.hasTopLevelForUpdate("SELECT * FROM orders FOR /* lock */ UPDATE"));
        assertFalse(SqlDmlRiskAnalyzer.hasTopLevelForUpdate("SELECT 'FOR UPDATE' AS note"));
        assertFalse(SqlDmlRiskAnalyzer.hasTopLevelForUpdate("SELECT q'[FOR UPDATE]' FROM dual"));
        assertFalse(SqlDmlRiskAnalyzer.hasTopLevelForUpdate("SELECT * FROM (SELECT * FROM orders FOR UPDATE) t"));
    }

    @Test
    void acceptsOnlySelectRootsForTemporaryResults() {
        assertTrue(SqlDmlRiskAnalyzer.isTopLevelReadOnlySelect("/* report */ SELECT * FROM orders"));
        assertTrue(SqlDmlRiskAnalyzer.isTopLevelReadOnlySelect("WITH items AS (SELECT * FROM orders) SELECT * FROM items"));
        assertFalse(SqlDmlRiskAnalyzer.isTopLevelReadOnlySelect("SHOW TABLES"));
        assertFalse(SqlDmlRiskAnalyzer.isTopLevelReadOnlySelect("EXPLAIN SELECT * FROM orders"));
        assertFalse(SqlDmlRiskAnalyzer.isTopLevelReadOnlySelect("WITH ids AS (SELECT 1) DELETE FROM orders"));
    }
}
