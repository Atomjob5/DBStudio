package com.dbstudio.oracle.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class OracleJdbcSessionTest {
    @Test
    void prefersTheCurrentSessionSchema() throws Exception {
        OracleJdbcSession session = new OracleJdbcSession(connection(
                "ALTERED_OWNER", null, "JDBC_OWNER", "LOGIN_OWNER"));

        assertEquals("ALTERED_OWNER", session.currentSchema());
    }

    @Test
    void fallsBackToJdbcSchemaWhenTheSessionQueryIsUnavailable() throws Exception {
        OracleJdbcSession session = new OracleJdbcSession(connection(
                "", new SQLException("SYS_CONTEXT unavailable"), "JDBC_OWNER", "LOGIN_OWNER"));

        assertEquals("JDBC_OWNER", session.currentSchema());
    }

    @Test
    void fallsBackToTheLoginUserAndRemovesOceanBaseTenantSuffixes() throws Exception {
        OracleJdbcSession session = new OracleJdbcSession(connection(
                "", null, "", "app@tenant#cluster"));

        assertEquals("app", session.currentSchema());
    }

    private static Connection connection(final String contextSchema, final SQLException queryFailure,
                                         final String jdbcSchema, final String username) {
        final DatabaseMetaData metadata = proxy(DatabaseMetaData.class, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                return "getUserName".equals(method.getName()) ? username : defaultValue(method.getReturnType());
            }
        });
        final Statement statement = proxy(Statement.class, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("executeQuery".equals(method.getName())) {
                    if (queryFailure != null) throw queryFailure;
                    return result(contextSchema);
                }
                return defaultValue(method.getReturnType());
            }
        });
        return proxy(Connection.class, new InvocationHandler() {
            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                if ("createStatement".equals(method.getName())) return statement;
                if ("getSchema".equals(method.getName())) return jdbcSchema;
                if ("getMetaData".equals(method.getName())) return metadata;
                return defaultValue(method.getReturnType());
            }
        });
    }

    private static ResultSet result(final String value) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private boolean beforeFirst = true;

            @Override public Object invoke(Object proxy, Method method, Object[] args) {
                if ("next".equals(method.getName())) {
                    boolean answer = beforeFirst;
                    beforeFirst = false;
                    return answer;
                }
                if ("getString".equals(method.getName())) return value;
                return defaultValue(method.getReturnType());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        return null;
    }
}
