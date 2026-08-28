package com.dbstudio.oracle.common;

import com.dbstudio.spi.ColumnInfo;
import com.dbstudio.spi.CompletionColumnComments;
import com.dbstudio.spi.CompletionMetadataListener;
import com.dbstudio.spi.DatabaseNamespace;
import com.dbstudio.spi.DatabaseObject;
import com.dbstudio.spi.DatabaseObjectType;
import com.dbstudio.spi.DatabaseSession;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OracleMetadataAdapterCompletionTest {
    private final OracleMetadataAdapter adapter = new OracleMetadataAdapter();

    @Test void discoversCompletionSchemasFromTabComments() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        List<DatabaseNamespace> namespaces = adapter.listCompletionNamespaces(session(jdbc.connection()));

        assertEquals("SELECT DISTINCT OWNER FROM ALL_TAB_COMMENTS ORDER BY OWNER", jdbc.sql.get(0));
        assertEquals(Arrays.asList("CBSAC", "CBSCM"), Arrays.asList(
                namespaces.get(0).schema(), namespaces.get(1).schema()));
    }

    @Test void streamsCommentsFirstAndNeverScansAllTabColumns() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        final List<String> events = new ArrayList<String>();
        final List<DatabaseObject> objects = new ArrayList<DatabaseObject>();
        final List<CompletionColumnComments> columns = new ArrayList<CompletionColumnComments>();
        adapter.streamCompletionMetadata(session(jdbc.connection()),
                Collections.singletonList(DatabaseNamespace.schema("CBSAC", true)),
                new LinkedHashSet<DatabaseObjectType>(Arrays.asList(
                        DatabaseObjectType.TABLE, DatabaseObjectType.VIEW)),
                new CompletionMetadataListener() {
                    @Override public void objects(List<DatabaseObject> values) {
                        events.add("objects");
                        objects.addAll(values);
                    }

                    @Override public void supplementalTables(List<DatabaseObject> values) {
                        events.add("tables");
                        objects.addAll(values);
                    }

                    @Override public void columns(List<CompletionColumnComments> values) {
                        events.add("columns");
                        columns.addAll(values);
                    }

                    @Override public void warning(String phase, String message) {
                        events.add("warning");
                    }
                });

        assertEquals(Arrays.asList("objects", "tables", "columns"), events);
        assertEquals(Arrays.asList("CUSTOMERS", "CUSTOMER_VIEW", "ORDERS"), Arrays.asList(
                objects.get(0).name(), objects.get(1).name(), objects.get(2).name()));
        assertEquals(3, columns.stream().mapToInt(value -> value.columns().size()).sum());
        assertTrue(jdbc.sql.get(0).startsWith(
                "SELECT OWNER,TABLE_NAME,TABLE_TYPE,COMMENTS FROM ALL_TAB_COMMENTS"));
        assertTrue(jdbc.sql.get(1).startsWith("SELECT OWNER,TABLE_NAME FROM ALL_TABLES"));
        assertEquals("SELECT * FROM ALL_COL_COMMENTS WHERE 1=0", jdbc.sql.get(2));
        assertTrue(jdbc.sql.get(3).startsWith(
                "SELECT OWNER,TABLE_NAME,COLUMN_NAME,COMMENTS FROM ALL_COL_COMMENTS"));
        assertFalse(jdbc.sql.stream().anyMatch(value -> value.contains("ALL_TAB_COLUMNS")));
        assertEquals(Collections.singletonList("CBSAC"), jdbc.parameters.get(jdbc.sql.get(0)));
    }

    @Test void loadsAndFormatsOnlyOneExactTableStructure() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        List<ColumnInfo> columns = adapter.listCompletionColumns(
                session(jdbc.connection()), "", "cbsac", "customers");

        assertEquals(2, columns.size());
        assertEquals("NUMBER(18,2)", columns.get(0).typeName());
        assertEquals("VARCHAR2(100 CHAR)", columns.get(1).typeName());
        String sql = jdbc.sql.get(0);
        assertTrue(sql.contains("FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=?"));
        assertEquals(Arrays.asList("CBSAC", "CUSTOMERS"), jdbc.parameters.get(sql));
    }

    @Test void fallsBackToUppercaseForUnquotedOracleTableMetadata() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.dictionaryComment = "账户号码";

        List<ColumnInfo> columns = adapter.listColumns(session(jdbc.connection()), "", "cbsltdcn1", "kdpa_acct_info");

        assertEquals(Collections.singletonList("LBLTY_ACCT_NUM"), Collections.singletonList(columns.get(0).name()));
        assertEquals("账户号码", columns.get(0).remarks());
        assertEquals(Arrays.asList(Arrays.asList("cbsltdcn1", "kdpa_acct_info"),
                Arrays.asList("CBSLTDCN1", "KDPA_ACCT_INFO")), jdbc.columnMetadataRequests);
    }

    @Test void keepsJdbcRemarkWhenDictionaryCommentIsEmpty() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.jdbcRemark = "JDBC备注";

        List<ColumnInfo> columns = adapter.listColumns(session(jdbc.connection()), "", "cbsltdcn1", "kdpa_acct_info");

        assertEquals("JDBC备注", columns.get(0).remarks());
    }

    @Test void keepsColumnsWhenColumnCommentDictionaryIsUnavailable() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.failColumnComments = true;

        List<ColumnInfo> columns = adapter.listColumns(session(jdbc.connection()), "", "cbsltdcn1", "kdpa_acct_info");

        assertEquals(1, columns.size());
        assertEquals("LBLTY_ACCT_NUM", columns.get(0).name());
    }

    @Test void supplementsColumnCommentsWhenOceanBaseUsesObjectNameColumn() throws Exception {
        FakeJdbc jdbc = new FakeJdbc("OBJECT_NAME");
        jdbc.dictionaryComment = "兼容库字段备注";

        List<ColumnInfo> columns = adapter.listColumns(session(jdbc.connection()), "", "cbsltdcn1", "kdpa_acct_info");

        assertEquals("兼容库字段备注", columns.get(0).remarks());
        assertTrue(jdbc.sql.stream().anyMatch(value -> value.startsWith(
                "SELECT COLUMN_NAME,COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER=? AND OBJECT_NAME=?")));
    }

    @Test void detectsOceanBaseObjectNameColumn() throws Exception {
        FakeJdbc jdbc = new FakeJdbc("OBJECT_NAME");
        adapter.streamCompletionMetadata(session(jdbc.connection()),
                Collections.singletonList(DatabaseNamespace.schema("CBSAC", true)),
                Collections.singleton(DatabaseObjectType.TABLE),
                new CompletionMetadataListener() {
                    @Override public void objects(List<DatabaseObject> values) { }
                    @Override public void supplementalTables(List<DatabaseObject> values) { }
                    @Override public void columns(List<CompletionColumnComments> values) { }
                    @Override public void warning(String phase, String message) { }
                });
        assertTrue(jdbc.sql.stream().anyMatch(value -> value.startsWith(
                "SELECT OWNER,OBJECT_NAME,COLUMN_NAME,COMMENTS FROM ALL_COL_COMMENTS")));
    }

    @Test void continuesWithColumnCommentsWhenAllTablesSupplementFails() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.failAllTables = true;
        final List<String> events = new ArrayList<String>();
        adapter.streamCompletionMetadata(session(jdbc.connection()),
                Collections.singletonList(DatabaseNamespace.schema("CBSAC", true)),
                Collections.singleton(DatabaseObjectType.TABLE),
                new CompletionMetadataListener() {
                    @Override public void objects(List<DatabaseObject> values) { events.add("objects"); }
                    @Override public void supplementalTables(List<DatabaseObject> values) { events.add("tables"); }
                    @Override public void columns(List<CompletionColumnComments> values) { events.add("columns"); }
                    @Override public void warning(String phase, String message) { events.add("warning:" + phase); }
                });
        assertEquals(Arrays.asList("objects", "warning:tables", "columns"), events);
    }

    private static DatabaseSession session(final Connection connection) {
        return new DatabaseSession() {
            @Override public Connection jdbcConnection() { return connection; }
            @Override public String currentCatalog() { return ""; }
            @Override public String currentSchema() { return "CBSAC"; }
            @Override public void close() { }
        };
    }

    private static final class FakeJdbc {
        private final List<String> sql = new ArrayList<String>();
        private final Map<String, List<String>> parameters = new LinkedHashMap<String, List<String>>();
        private final List<List<String>> columnMetadataRequests = new ArrayList<List<String>>();
        private final String commentObjectColumn;
        private boolean failAllTables;
        private boolean failColumnComments;
        private String jdbcRemark = "";
        private String dictionaryComment = "";

        private FakeJdbc() {
            this("TABLE_NAME");
        }

        private FakeJdbc(String commentObjectColumn) {
            this.commentObjectColumn = commentObjectColumn;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, arguments) -> {
                if ("createStatement".equals(method.getName())) return statement();
                if ("prepareStatement".equals(method.getName())) return prepared(String.valueOf(arguments[0]));
                if ("getMetaData".equals(method.getName())) return metadata();
                if ("getSchema".equals(method.getName())) return "CBSAC";
                return defaultValue(method.getReturnType());
            });
        }

        private DatabaseMetaData metadata() {
            return proxy(DatabaseMetaData.class, (proxy, method, arguments) -> {
                if ("getColumns".equals(method.getName())) {
                    String owner = String.valueOf(arguments[1]);
                    String table = String.valueOf(arguments[2]);
                    columnMetadataRequests.add(Arrays.asList(owner, table));
                    if ("CBSLTDCN1".equals(owner) && "KDPA_ACCT_INFO".equals(table)) {
                        return resultSet(Arrays.asList("COLUMN_NAME", "TYPE_NAME", "COLUMN_SIZE", "DECIMAL_DIGITS",
                                "NULLABLE", "COLUMN_DEF", "ORDINAL_POSITION", "REMARKS", "IS_AUTOINCREMENT",
                                "IS_GENERATEDCOLUMN"), Collections.singletonList(Arrays.<Object>asList(
                                "LBLTY_ACCT_NUM", "VARCHAR2", 64, 0, DatabaseMetaData.columnNullable,
                                null, 1, jdbcRemark, "NO", "NO")));
                    }
                    return resultSet(Collections.<String>emptyList(), Collections.<List<Object>>emptyList());
                }
                return defaultValue(method.getReturnType());
            });
        }

        private Statement statement() {
            return proxy(Statement.class, (proxy, method, arguments) -> {
                if ("executeQuery".equals(method.getName())) return result(String.valueOf(arguments[0]));
                return defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement prepared(final String query) {
            final Map<Integer, String> values = new LinkedHashMap<Integer, String>();
            return proxy(PreparedStatement.class, (proxy, method, arguments) -> {
                if ("setString".equals(method.getName())) {
                    values.put((Integer) arguments[0], String.valueOf(arguments[1]));
                    return null;
                }
                if ("executeQuery".equals(method.getName())) {
                    List<String> ordered = new ArrayList<String>();
                    for (int index = 1; index <= values.size(); index++) ordered.add(values.get(index));
                    parameters.put(query, ordered);
                    if (failAllTables && query.startsWith("SELECT OWNER,TABLE_NAME FROM ALL_TABLES")) {
                        throw new java.sql.SQLException("ALL_TABLES unavailable");
                    }
                    if (failColumnComments && query.startsWith("SELECT COLUMN_NAME,COMMENTS FROM ALL_COL_COMMENTS")) {
                        throw new java.sql.SQLException("ALL_COL_COMMENTS unavailable");
                    }
                    return result(query);
                }
                return defaultValue(method.getReturnType());
            });
        }

        private ResultSet result(String query) {
            sql.add(query);
            final List<String> labels;
            final List<List<Object>> rows = new ArrayList<List<Object>>();
            if (query.startsWith("SELECT DISTINCT OWNER FROM ALL_TAB_COMMENTS")) {
                labels = Collections.singletonList("OWNER");
                rows.add(Collections.<Object>singletonList("CBSAC"));
                rows.add(Collections.<Object>singletonList("CBSCM"));
            } else if (query.startsWith("SELECT OWNER,TABLE_NAME,TABLE_TYPE,COMMENTS FROM ALL_TAB_COMMENTS")) {
                labels = Arrays.asList("OWNER", "TABLE_NAME", "TABLE_TYPE", "COMMENTS");
                rows.add(Arrays.<Object>asList("CBSAC", "CUSTOMERS", "TABLE", "客户"));
                rows.add(Arrays.<Object>asList("CBSAC", "CUSTOMER_VIEW", "VIEW", "客户视图"));
            } else if (query.startsWith("SELECT OWNER,TABLE_NAME FROM ALL_TABLES")) {
                labels = Arrays.asList("OWNER", "TABLE_NAME");
                rows.add(Arrays.<Object>asList("CBSAC", "CUSTOMERS"));
                rows.add(Arrays.<Object>asList("CBSAC", "ORDERS"));
            } else if (query.equals("SELECT * FROM ALL_COL_COMMENTS WHERE 1=0")) {
                labels = Arrays.asList("OWNER", commentObjectColumn, "COLUMN_NAME", "COMMENTS");
            } else if (query.startsWith("SELECT COLUMN_NAME,COMMENTS FROM ALL_COL_COMMENTS WHERE OWNER=? AND")) {
                labels = Arrays.asList("COLUMN_NAME", "COMMENTS");
                rows.add(Arrays.<Object>asList("LBLTY_ACCT_NUM", dictionaryComment));
            } else if (query.startsWith("SELECT OWNER," + commentObjectColumn
                    + ",COLUMN_NAME,COMMENTS FROM ALL_COL_COMMENTS")) {
                labels = Arrays.asList("OWNER", commentObjectColumn, "COLUMN_NAME", "COMMENTS");
                rows.add(Arrays.<Object>asList("CBSAC", "CUSTOMERS", "ID", "客户编号"));
                rows.add(Arrays.<Object>asList("CBSAC", "CUSTOMER_VIEW", "NAME", "客户名称"));
                rows.add(Arrays.<Object>asList("CBSAC", "ORDERS", "ID", "订单编号"));
            } else if (query.contains("FROM ALL_TAB_COLUMNS WHERE OWNER=? AND TABLE_NAME=?")) {
                labels = Arrays.asList("COLUMN_NAME", "DATA_TYPE", "DATA_LENGTH", "DATA_PRECISION",
                        "DATA_SCALE", "CHAR_LENGTH", "CHAR_USED", "COLUMN_ID");
                rows.add(Arrays.<Object>asList("AMOUNT", "NUMBER", 22, 18, 2, 0, null, 1));
                rows.add(Arrays.<Object>asList("NAME", "VARCHAR2", 400, null, 0, 100, "C", 2));
            } else if (query.startsWith("SELECT c.CONSTRAINT_NAME,c.CONSTRAINT_TYPE,cc.COLUMN_NAME,cc.POSITION")) {
                labels = Arrays.asList("CONSTRAINT_NAME", "CONSTRAINT_TYPE", "COLUMN_NAME", "POSITION");
            } else {
                throw new AssertionError("Unexpected SQL: " + query);
            }
            return resultSet(labels, rows);
        }
    }

    private static ResultSet resultSet(final List<String> labels, final List<List<Object>> rows) {
        return proxy(ResultSet.class, new InvocationHandler() {
            private int cursor = -1;

            @Override public Object invoke(Object proxy, Method method, Object[] arguments) {
                String name = method.getName();
                if ("next".equals(name)) return ++cursor < rows.size();
                if ("getMetaData".equals(name)) return resultMetadata(labels);
                if ("getObject".equals(name)) return value(arguments[0]);
                if ("getString".equals(name)) {
                    Object value = value(arguments[0]);
                    return value == null ? null : String.valueOf(value);
                }
                if ("getInt".equals(name)) {
                    Object value = value(arguments[0]);
                    return value instanceof Number ? ((Number) value).intValue() : 0;
                }
                if ("wasNull".equals(name)) return false;
                return defaultValue(method.getReturnType());
            }

            private Object value(Object key) {
                int index;
                if (key instanceof Number) index = ((Number) key).intValue() - 1;
                else index = labels.indexOf(String.valueOf(key));
                return cursor < 0 || cursor >= rows.size() || index < 0 ? null : rows.get(cursor).get(index);
            }
        });
    }

    private static ResultSetMetaData resultMetadata(final List<String> labels) {
        return proxy(ResultSetMetaData.class, (proxy, method, arguments) -> {
            if ("getColumnCount".equals(method.getName())) return labels.size();
            if ("getColumnLabel".equals(method.getName())) return labels.get((Integer) arguments[0] - 1);
            return defaultValue(method.getReturnType());
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
