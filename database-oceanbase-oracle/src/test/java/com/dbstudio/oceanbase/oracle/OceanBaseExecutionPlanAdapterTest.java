package com.dbstudio.oceanbase.oracle;

import com.dbstudio.spi.ExecutionPlan;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OceanBaseExecutionPlanAdapterTest {
    private final OceanBaseExecutionPlanAdapter adapter = new OceanBaseExecutionPlanAdapter();
    @Test void parsesIndentedOperatorsWithoutInventingMissingCosts() {
        String text = "|ID|OPERATOR        |NAME|EST.ROWS|EST.TIME(us)|\n"
                + "|0 |HASH JOIN       |    |10      |20          |\n"
                + "|1 | TABLE SCAN     |A   |100     |10          |\n"
                + "|2 | TABLE GET      |B   |1       |2           |\n"
                + "Outputs & filters:\n  0 - output([A.ID]), filter(nil)\n"
                + "  1 - output([A.ID]), filter([A.ID > 2])\n  2 - output([B.ID]), filter(nil)\n";
        ExecutionPlan plan = adapter.parse("SELECT * FROM a JOIN b ON a.id=b.id", text);
        assertEquals(3, plan.getNodes().size());
        assertEquals("0", plan.getNodes().get(1).getParentId());
        assertEquals("0", plan.getNodes().get(2).getParentId());
        assertEquals("", plan.getNodes().get(1).getCost());
        assertEquals(text, plan.getRawText());
        assertEquals("[A.ID > 2]", plan.getNodes().get(1).getCondition());
    }
    @Test void fallsBackWhenHierarchyCannotBeRead() {
        ExecutionPlan plan = adapter.parse("select 1", "|ID|OPERATOR|NAME|\n|0|JOIN||\n|1|SCAN|T|\n");
        assertTrue(plan.getNodes().isEmpty()); assertFalse(plan.getWarning().isEmpty());
    }
    @Test void validatesOracleDmlAndRejectsScripts() throws Exception {
        adapter.validate("MERGE INTO t USING s ON (t.id=s.id) WHEN MATCHED THEN UPDATE SET t.name=s.name");
        assertThrows(SQLException.class, () -> adapter.validate("DELETE FROM t; COMMIT"));
        assertThrows(SQLException.class, () -> adapter.validate("TRUNCATE TABLE t"));
    }
}
