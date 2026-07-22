package com.dbstudio.oceanbase.oracle;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.contract.DatabaseProviderContract;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OceanBaseOracleProviderTest {
    @Test void buildsExplicitOracleModeUrlAndLoadsProvider() {
        Map<String, String> settings = new LinkedHashMap<String, String>();
        settings.put("host", "ob.example"); settings.put("port", "2881"); settings.put("database", "ORACLE");
        settings.put("username", "app@tenant");
        ConnectionProfile profile = new ConnectionProfile(UUID.randomUUID(), "oceanbase-oracle", "test", settings, "test");
        assertEquals("jdbc:oceanbase:oracle://ob.example:2881/ORACLE",
                OceanBaseOracleConnectionAdapter.buildJdbcUrl(profile));
        DatabaseProviderContract.verifyDescriptor(new OceanBaseOracleDatabaseProvider());
        boolean found = false;
        for (DatabaseProvider provider : ServiceLoader.load(DatabaseProvider.class)) {
            if ("oceanbase-oracle".equals(provider.id())) found = true;
        }
        assertTrue(found);
    }
}
