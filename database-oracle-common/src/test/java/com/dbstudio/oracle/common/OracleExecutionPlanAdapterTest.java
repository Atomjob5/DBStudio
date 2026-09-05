package com.dbstudio.oracle.common;

import com.dbstudio.spi.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OracleExecutionPlanAdapterTest {
    @Test void cancellationBeforeStartingDoesNotTouchTransaction() {
        for (boolean auto : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(auto, "cancel-before-start");
            assertThrows(SQLException.class, () -> new OracleExecutionPlanAdapter()
                    .explain(fixture.session(), "SELECT 1 FROM dual", fixture));
            assertEquals(auto, fixture.auto);
            assertEquals(0, fixture.fullRollbacks + fixture.localRollbacks + fixture.commits);
            assertTrue(fixture.executed.isEmpty());
        }
    }
    @Test void preservesUserTransactionOnSuccessFailureAndCancellation() throws Exception {
        for (boolean auto : new boolean[] { false, true }) {
            for (String failure : new String[] { "", "execute", "executeQuery", "cancel" }) {
                Fixture fixture = new Fixture(auto, failure);
                OracleExecutionPlanAdapter adapter = new OracleExecutionPlanAdapter();
                if (failure.isEmpty()) {
                    ExecutionPlan plan = adapter.explain(fixture.session(), "UPDATE t SET name='x'", fixture);
                    assertEquals(1, plan.getNodes().size());
                    assertEquals("TABLE ACCESS", plan.getNodes().get(0).getOperation());
                    assertEquals("PLAN TEXT\n", plan.getRawText());
                } else assertThrows(SQLException.class, () -> adapter.explain(fixture.session(), "DELETE FROM t", fixture));
                assertTrue(fixture.cleaned);
                assertEquals(auto, fixture.auto);
                assertEquals(auto ? 1 : 0, fixture.fullRollbacks);
                assertEquals(auto ? 0 : 1, fixture.localRollbacks);
                assertEquals(0, fixture.commits);
                assertTrue(fixture.executed.stream().allMatch(sql -> sql.startsWith("EXPLAIN PLAN SET STATEMENT_ID = 'DBS_")));
            }
        }
    }
    @Test void validatesSupportedShapes() throws Exception {
        OracleExecutionPlanAdapter adapter = new OracleExecutionPlanAdapter();
        for (String sql : new String[] { "SELECT 1 FROM dual", "WITH c AS (SELECT 1 id FROM dual) SELECT * FROM c",
                "SELECT count(*) FROM t GROUP BY id", "SELECT * FROM a JOIN b ON a.id=b.id",
                "SELECT * FROM t WHERE id IN (SELECT id FROM b)", "INSERT INTO t VALUES (1)",
                "UPDATE t SET id=2", "DELETE FROM t", "MERGE INTO t USING s ON (t.id=s.id) WHEN MATCHED THEN UPDATE SET t.name=s.name" }) adapter.validate(sql);
        for (String sql : new String[] { "SELECT 1 FROM dual; DELETE FROM t", "BEGIN NULL; END;", "DROP TABLE t", "COMMIT" })
            assertThrows(SQLException.class, () -> adapter.validate(sql));
    }

    private static final class Fixture implements ExecutionPlanAdapter.Control {
        boolean auto, cleaned; final String failure; int statements, fullRollbacks, localRollbacks, commits;
        final List<String> executed = new ArrayList<String>();
        final Savepoint savepoint = proxy(Savepoint.class, (p,m,a) -> null);
        Fixture(boolean auto, String failure) { this.auto=auto; this.failure=failure; }
        final Connection connection = proxy(Connection.class, (p,m,a) -> {
            switch (m.getName()) {
                case "getAutoCommit": return auto;
                case "setAutoCommit": auto = (Boolean)a[0]; return null;
                case "setSavepoint": return savepoint;
                case "rollback":
                    if (a == null || a.length == 0) fullRollbacks++;
                    else { assertSame(savepoint, a[0]); localRollbacks++; } return null;
                case "commit": commits++; return null;
                case "createStatement": return statement(false, false);
                case "prepareStatement": return statement(true, ((String)a[0]).contains("DBMS_XPLAN"));
                default: return null;
            }
        });
        Object statement(boolean prepared, boolean text) {
            return proxy(PreparedStatement.class, (p,m,a) -> {
                if (m.getName().equals(failure)) throw new SQLException("permission denied");
                if (m.getName().equals("execute")) { executed.add((String)a[0]); return false; }
                if (m.getName().equals("executeQuery")) {
                    final int[] index = {0};
                    String[] data = text ? new String[] {"PLAN TEXT"}
                            : new String[] {"0", null, "TABLE ACCESS", "FULL", "APP", "T", "3", "1", null, "ID=1"};
                    return proxy(ResultSet.class, (r,rm,ra) -> {
                        if (rm.getName().equals("next")) return index[0]++ == 0;
                        if (rm.getName().equals("getString")) return data[(Integer)ra[0]-1];
                        return null;
                    });
                }
                return null;
            });
        }
        DatabaseSession session() { return new DatabaseSession() {
            public Connection jdbcConnection() { return connection; }
            public String currentCatalog() { return ""; }
            public void close() { }
        }; }
        public void active(Statement statement) throws SQLException { statements++; checkCancelled(); }
        public void checkCancelled() throws SQLException {
            if (failure.equals("cancel-before-start") || failure.equals("cancel") && statements > 0)
                throw new SQLException("cancelled");
        }
        public void cleanup() { cleaned=true; }
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
