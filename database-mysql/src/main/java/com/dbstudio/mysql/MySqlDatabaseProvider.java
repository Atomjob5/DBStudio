package com.dbstudio.mysql;

import com.dbstudio.spi.ConnectionAdapter;
import com.dbstudio.spi.ConnectionField;
import com.dbstudio.spi.DatabaseCapabilities;
import com.dbstudio.spi.DatabaseCapability;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.FieldType;
import com.dbstudio.spi.MetadataAdapter;
import com.dbstudio.spi.SqlDialect;
import java.util.List;
import java.util.Arrays;

public final class MySqlDatabaseProvider implements DatabaseProvider {
    private final ConnectionAdapter connections = new MySqlConnectionAdapter();
    private final MetadataAdapter metadata = new MySqlMetadataAdapter();
    private final SqlDialect dialect = new MySqlDialect();

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public String displayName() {
        return "MySQL 8";
    }

    @Override
    public List<ConnectionField> connectionFields() {
        return Arrays.asList(
                new ConnectionField("host", "主机", FieldType.TEXT, true, "127.0.0.1", "MySQL 主机名或 IP"),
                new ConnectionField("port", "端口", FieldType.INTEGER, true, "3306", "MySQL TCP 端口"),
                new ConnectionField("database", "数据库", FieldType.TEXT, false, "", "连接后默认选择的数据库"),
                new ConnectionField("username", "用户名", FieldType.TEXT, true, "root", "数据库用户名"),
                new ConnectionField("password", "密码", FieldType.PASSWORD, false, "", "密码不会保存到连接配置"),
                new ConnectionField("timeoutSeconds", "连接超时（秒）", FieldType.INTEGER, true, "10", "建立连接的最长等待时间"));
    }

    @Override
    public DatabaseCapabilities capabilities() {
        return DatabaseCapabilities.of(
                DatabaseCapability.CATALOGS,
                DatabaseCapability.TABLES,
                DatabaseCapability.VIEWS,
                DatabaseCapability.INDEXES,
                DatabaseCapability.CONSTRAINTS,
                DatabaseCapability.TRIGGERS,
                DatabaseCapability.PROCEDURES,
                DatabaseCapability.FUNCTIONS,
                DatabaseCapability.EXPLAIN_PLAN);
    }

    @Override
    public ConnectionAdapter connections() {
        return connections;
    }

    @Override
    public MetadataAdapter metadata() {
        return metadata;
    }

    @Override
    public SqlDialect dialect() {
        return dialect;
    }

    @Override public com.dbstudio.spi.ExecutionPlanAdapter executionPlans() { return new MySqlExecutionPlanAdapter(); }
}
