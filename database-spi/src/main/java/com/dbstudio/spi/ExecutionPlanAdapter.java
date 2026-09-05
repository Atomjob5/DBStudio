package com.dbstudio.spi;

import java.sql.*;

public interface ExecutionPlanAdapter {
    /** Reject unsupported or ambiguous input before scheduling any JDBC work. */
    void validate(String sql) throws SQLException;
    ExecutionPlan explain(DatabaseSession session, String sql, Control control) throws SQLException;

    interface Control {
        /** Registers every active JDBC statement and honours cancellation between statements. */
        void active(Statement statement) throws SQLException;
        void checkCancelled() throws SQLException;
        /** Cleanup must finish even after cancellation; it must never cancel user transactions. */
        void cleanup();
    }
}
