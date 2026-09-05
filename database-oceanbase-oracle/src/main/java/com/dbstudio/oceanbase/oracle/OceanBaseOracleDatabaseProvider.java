package com.dbstudio.oceanbase.oracle;

import com.dbstudio.oracle.common.OracleDialect;
import com.dbstudio.oracle.common.OracleMetadataAdapter;
import com.dbstudio.spi.ConnectionAdapter;
import com.dbstudio.spi.ConnectionField;
import com.dbstudio.spi.DatabaseCapabilities;
import com.dbstudio.spi.DatabaseCapability;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.FieldType;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.SqlDialect;
import java.util.Arrays;
import java.util.List;

public final class OceanBaseOracleDatabaseProvider implements DatabaseProvider {
    private final ConnectionAdapter connections = new OceanBaseOracleConnectionAdapter();
    private final MetadataAdapter metadata = new OracleMetadataAdapter();
    private final SqlDialect dialect = new OceanBaseOracleDialect();
    @Override public String id() { return "oceanbase-oracle"; }
    @Override public String displayName() { return "OceanBase Oracle"; }
    @Override public List<ConnectionField> connectionFields() {
        return Arrays.asList(
                new ConnectionField("host", "主机", FieldType.TEXT, true, "127.0.0.1", "OBServer或ODP主机名/IP"),
                new ConnectionField("port", "端口", FieldType.INTEGER, true, "2881", "OceanBase SQL端口"),
                new ConnectionField("database", "数据库 / 服务名", FieldType.TEXT, true, "", "Oracle租户连接的数据库或服务名"),
                new ConnectionField("username", "用户名", FieldType.TEXT, true, "", "可包含租户和集群信息"),
                new ConnectionField("password", "密码", FieldType.PASSWORD, false, "", "密码不会保存到连接配置"),
                new ConnectionField("schema", "默认Schema", FieldType.TEXT, false, "", "留空时使用登录用户名"),
                new ConnectionField("timeoutSeconds", "连接超时（秒）", FieldType.INTEGER, true, "10", "建立连接的最长等待时间"));
    }
    @Override public DatabaseCapabilities capabilities() {
        return DatabaseCapabilities.of(DatabaseCapability.SCHEMAS, DatabaseCapability.TABLES, DatabaseCapability.VIEWS,
                DatabaseCapability.INDEXES, DatabaseCapability.CONSTRAINTS, DatabaseCapability.TRIGGERS,
                DatabaseCapability.PROCEDURES, DatabaseCapability.FUNCTIONS, DatabaseCapability.PACKAGES,
                DatabaseCapability.SEQUENCES, DatabaseCapability.SYNONYMS, DatabaseCapability.TYPES,
                DatabaseCapability.EXPLAIN_PLAN);
    }
    @Override public ConnectionAdapter connections() { return connections; }
    @Override public MetadataAdapter metadata() { return metadata; }
    @Override public SqlDialect dialect() { return dialect; }
    @Override public com.dbstudio.spi.ExecutionPlanAdapter executionPlans() { return new OceanBaseExecutionPlanAdapter(); }

    private static final class OceanBaseOracleDialect extends OracleDialect {
        @Override public String id() { return "oceanbase-oracle"; }
    }
}
