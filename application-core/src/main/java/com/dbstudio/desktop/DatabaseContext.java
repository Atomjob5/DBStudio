package com.dbstudio.desktop;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.spi.SqlLogCategory;
import com.dbstudio.spi.SqlLogging;
import com.dbstudio.desktop.query.MetadataResultColumnResolver;
import com.dbstudio.desktop.query.ResultColumnResolver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个连接配置对应的数据库上下文。
 *
 * <p>元数据会话与编辑器 JDBC 会话分离：前者按需创建并可安全回收，后者由 Workspace
 * JDBC 队列借用。密码只在内存中保存，关闭上下文时立即清零。</p>
 */
public final class DatabaseContext implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseContext.class);
    private final DatabaseProvider provider;
    private final ConnectionProfile profile;
    private final char[] password;
    private volatile DatabaseSession metadataSession;
    private volatile ResultColumnResolver resultColumnResolver;

    public DatabaseContext(DatabaseProvider provider, ConnectionProfile profile, char[] password) throws SQLException {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.password = Arrays.copyOf(password, password.length);
    }

    public DatabaseProvider provider() {
        return provider;
    }

    public ConnectionProfile profile() {
        return profile;
    }

    public synchronized DatabaseSession metadataSession() throws SQLException {
        if (metadataSession == null || metadataSession.isClosed()) {
            LOG.debug("按需创建元数据连接 provider={} profileId={}", provider.id(), profile.id());
            openMetadataSession();
        }
        return metadataSession;
    }

    public synchronized ResultColumnResolver resultColumnResolver() throws SQLException {
        metadataSession();
        return resultColumnResolver;
    }

    public DatabaseSession openEditorSession() throws SQLException {
        LOG.info("创建编辑器JDBC连接 provider={} profileId={}", provider.id(), profile.id());
        DatabaseSession opened = provider.connections().connect(profile, password);
        SqlLogging.categorize(opened.jdbcConnection(), SqlLogCategory.BUSINESS);
        return opened;
    }

    /** 释放辅助元数据连接；下一次访问元数据时按需重新创建。 */
    public synchronized void suspendMetadata() {
        DatabaseSession current = metadataSession;
        metadataSession = null;
        resultColumnResolver = null;
        if (current != null) {
            synchronized (current) {
                try { current.close(); }
                catch (SQLException exception) { LOG.warn("关闭元数据连接失败 profileId={}", profile.id(), exception); }
            }
        }
    }

    private void openMetadataSession() throws SQLException {
        DatabaseSession opened = provider.connections().connect(profile, password);
        SqlLogging.categorize(opened.jdbcConnection(), SqlLogCategory.METADATA);
        metadataSession = opened;
        resultColumnResolver = new MetadataResultColumnResolver(provider.metadata(), opened, provider.dialect());
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
        suspendMetadata();
        LOG.debug("数据库上下文已关闭 provider={} profileId={}", provider.id(), profile.id());
    }
}
