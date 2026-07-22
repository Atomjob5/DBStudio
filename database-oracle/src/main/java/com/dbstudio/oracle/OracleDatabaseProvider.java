package com.dbstudio.oracle;

import com.dbstudio.oracle.common.OracleDialect;
import com.dbstudio.oracle.common.OracleMetadataAdapter;
import com.dbstudio.spi.ConnectionAdapter;
import com.dbstudio.spi.ConnectionField;
import com.dbstudio.spi.ConnectionFieldOption;
import com.dbstudio.spi.DatabaseCapabilities;
import com.dbstudio.spi.DatabaseCapability;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.FieldType;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.SqlDialect;
import java.util.Arrays;
import java.util.List;

public final class OracleDatabaseProvider implements DatabaseProvider {
    private final ConnectionAdapter connections = new OracleConnectionAdapter();
    private final MetadataAdapter metadata = new OracleMetadataAdapter();
    private final SqlDialect dialect = new OracleDialect();
    @Override public String id() { return "oracle"; }
    @Override public String displayName() { return "Oracle 19c / 21c"; }
    @Override public List<ConnectionField> connectionFields() {
        return Arrays.asList(
                new ConnectionField("host", "主机", FieldType.TEXT, true, "127.0.0.1", "Oracle主机名或IP"),
                new ConnectionField("port", "端口", FieldType.INTEGER, true, "1521", "Oracle Listener端口"),
                new ConnectionField("connectionMode", "连接方式", FieldType.SELECT, true, "service", "选择Service Name或SID",
                        Arrays.asList(new ConnectionFieldOption("service", "Service Name"), new ConnectionFieldOption("sid", "SID"))),
                new ConnectionField("service", "Service Name / SID", FieldType.TEXT, true, "ORCL", "数据库服务名或SID"),
                new ConnectionField("username", "用户名", FieldType.TEXT, true, "", "Oracle用户名"),
                new ConnectionField("password", "密码", FieldType.PASSWORD, false, "", "密码不会保存到连接配置"),
                new ConnectionField("schema", "默认Schema", FieldType.TEXT, false, "", "留空时使用登录用户名"),
                new ConnectionField("timeoutSeconds", "连接超时（秒）", FieldType.INTEGER, true, "10", "建立连接的最长等待时间"));
    }
    @Override public DatabaseCapabilities capabilities() { return oracleCapabilities(); }
    public static DatabaseCapabilities oracleCapabilities() {
        return DatabaseCapabilities.of(DatabaseCapability.SCHEMAS, DatabaseCapability.TABLES, DatabaseCapability.VIEWS,
                DatabaseCapability.INDEXES, DatabaseCapability.CONSTRAINTS, DatabaseCapability.TRIGGERS,
                DatabaseCapability.PROCEDURES, DatabaseCapability.FUNCTIONS, DatabaseCapability.PACKAGES,
                DatabaseCapability.SEQUENCES, DatabaseCapability.SYNONYMS, DatabaseCapability.TYPES,
                DatabaseCapability.EXPLAIN_PLAN);
    }
    @Override public ConnectionAdapter connections() { return connections; }
    @Override public MetadataAdapter metadata() { return metadata; }
    @Override public SqlDialect dialect() { return dialect; }
}
