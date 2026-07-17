package com.dbstudio.mysql;

import com.dbstudio.spi.ConnectionProfile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MySqlConnectionAdapterTest {
    @Test
    void buildsUrlWithoutCredentialsOrCatalog() {
        Map<String, String> settings = new HashMap<String, String>();
        settings.put("host", "db.internal"); settings.put("port", "3307");
        settings.put("username", "alice"); settings.put("database", "secret-db");
        ConnectionProfile profile = new ConnectionProfile(
                UUID.randomUUID(), "mysql", "local",
                settings, "secret");

        assertEquals("jdbc:mysql://db.internal:3307/", MySqlConnectionAdapter.buildJdbcUrl(profile));
    }

    @Test
    void rejectsHostThatCouldInjectUrlProperties() {
        Map<String, String> settings = new HashMap<String, String>();
        settings.put("host", "localhost?password=oops"); settings.put("username", "root");
        ConnectionProfile profile = new ConnectionProfile(
                UUID.randomUUID(), "mysql", "bad",
                settings, "secret");

        assertThrows(IllegalArgumentException.class, () -> MySqlConnectionAdapter.buildJdbcUrl(profile));
    }
}
