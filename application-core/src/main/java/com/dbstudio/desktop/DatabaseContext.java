package com.dbstudio.desktop;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.DatabaseSession;
import com.dbstudio.desktop.query.MetadataResultColumnResolver;
import com.dbstudio.desktop.query.ResultColumnResolver;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

public final class DatabaseContext implements AutoCloseable {
    private final DatabaseProvider provider;
    private final ConnectionProfile profile;
    private final char[] password;
    private volatile DatabaseSession metadataSession;
    private volatile ResultColumnResolver resultColumnResolver;

    public DatabaseContext(DatabaseProvider provider, ConnectionProfile profile, char[] password) throws SQLException {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.password = Arrays.copyOf(password, password.length);
        openMetadataSession();
    }

    public DatabaseProvider provider() {
        return provider;
    }

    public ConnectionProfile profile() {
        return profile;
    }

    public synchronized DatabaseSession metadataSession() throws SQLException {
        if (metadataSession == null || metadataSession.isClosed()) openMetadataSession();
        return metadataSession;
    }

    public synchronized ResultColumnResolver resultColumnResolver() throws SQLException {
        metadataSession();
        return resultColumnResolver;
    }

    public DatabaseSession openEditorSession() throws SQLException {
        return provider.connections().connect(profile, password);
    }

    /** Releases the auxiliary metadata connection; it is recreated on the next metadata access. */
    public synchronized void suspendMetadata() {
        DatabaseSession current = metadataSession;
        metadataSession = null;
        resultColumnResolver = null;
        if (current != null) {
            synchronized (current) {
                try { current.close(); } catch (SQLException ignored) { }
            }
        }
    }

    private void openMetadataSession() throws SQLException {
        DatabaseSession opened = provider.connections().connect(profile, password);
        metadataSession = opened;
        resultColumnResolver = new MetadataResultColumnResolver(provider.metadata(), opened, provider.dialect());
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
        suspendMetadata();
    }
}
