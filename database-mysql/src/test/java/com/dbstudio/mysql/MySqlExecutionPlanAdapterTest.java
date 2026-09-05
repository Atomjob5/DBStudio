package com.dbstudio.mysql;

import com.dbstudio.spi.ExecutionPlan;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MySqlExecutionPlanAdapterTest {
    private final MySqlExecutionPlanAdapter adapter = new MySqlExecutionPlanAdapter();
    @Test void validatesOnlySingleExplainableStatements() throws Exception {
        for (String sql : new String[] { "SELECT * FROM t", "WITH c AS (SELECT 1) SELECT * FROM c",
                "SELECT count(*) FROM t GROUP BY id", "SELECT * FROM a JOIN b ON a.id=b.id",
                "SELECT * FROM t WHERE id IN (SELECT id FROM b)", "INSERT INTO t VALUES (1)",
                "UPDATE t SET id=2", "DELETE FROM t" }) adapter.validate(sql);
        for (String sql : new String[] { "DROP TABLE t", "SELECT 1; DELETE FROM t", "CALL p()", "COMMIT",
                "EXPLAIN ANALYZE SELECT 1", "MERGE INTO t USING s ON (t.id=s.id) WHEN MATCHED THEN UPDATE SET t.id=1" })
            assertThrows(SQLException.class, () -> adapter.validate(sql));
    }
    @Test void preservesNestedPlanHierarchyAndUnknownMetrics() {
        ExecutionPlan plan = adapter.parse("select ...", "{\"query_block\":{\"cost_info\":{\"query_cost\":\"3.5\"},"
                + "\"nested_loop\":[{\"table\":{\"table_name\":\"a\",\"access_type\":\"ALL\",\"rows_examined_per_scan\":0}},"
                + "{\"table\":{\"table_name\":\"b\",\"key\":\"PRIMARY\",\"attached_condition\":\"b.id=a.id\"}}]}}");
        assertEquals("", plan.getWarning()); assertEquals(4, plan.getNodes().size());
        assertEquals("3.5", plan.getNodes().get(0).getCost());
        assertEquals("1", plan.getNodes().get(2).getParentId());
        assertEquals("0", plan.getNodes().get(2).getEstimatedRows());
        assertNull(plan.getNodes().get(3).getEstimatedRows());
        assertEquals("PRIMARY", plan.getNodes().get(3).getIndex());
    }
    @Test void malformedPlanRemainsAvailableAsRawText() {
        ExecutionPlan plan = adapter.parse("select 1", "unrecognised plan");
        assertEquals("unrecognised plan", plan.getRawText());
        assertFalse(plan.getWarning().isEmpty()); assertTrue(plan.getNodes().isEmpty());
    }
}
