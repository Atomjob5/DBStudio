package com.dbstudio.desktop;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.DatabaseSession;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

public final class DatabaseContext implements AutoCloseable {
    private final DatabaseProvider provider;
    private final ConnectionProfile profile;
    private final char[] password;
    private final DatabaseSession metadataSession;

    public DatabaseContext(DatabaseProvider provider, ConnectionProfile profile, char[] password) throws SQLException {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.password = Arrays.copyOf(password, password.length);
        this.metadataSession = provider.connections().connect(profile, this.password);
    }

    public DatabaseProvider provider() {
        return provider;
    }

    public ConnectionProfile profile() {
        return profile;
    }

    public DatabaseSession metadataSession() {
        return metadataSession;
    }

    public DatabaseSession openEditorSession() throws SQLException {
        return provider.connections().connect(profile, password);
    }

    @Override
    public void close() {
        Arrays.fill(password, '\0');
        try {
            metadataSession.close();
        } catch (SQLException ignored) {
            // Connection teardown is best effort.
        }
    }
}
