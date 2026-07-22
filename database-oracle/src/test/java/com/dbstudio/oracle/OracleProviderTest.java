package com.dbstudio.oracle;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.FieldType;
import com.dbstudio.spi.contract.DatabaseProviderContract;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OracleProviderTest {
    @Test void buildsServiceAndSidUrls() {
        Map<String, String> settings = settings();
        ConnectionProfile service = profile(settings);
        assertEquals("jdbc:oracle:thin:@//db.example:1521/ORCLPDB1", OracleConnectionAdapter.buildJdbcUrl(service));
        settings.put("connectionMode", "sid");
        assertEquals("jdbc:oracle:thin:@db.example:1521:ORCLPDB1", OracleConnectionAdapter.buildJdbcUrl(profile(settings)));
    }

    @Test void exposesSelectFieldAndServiceLoaderEntry() {
        OracleDatabaseProvider provider = new OracleDatabaseProvider();
        DatabaseProviderContract.verifyDescriptor(provider);
        assertTrue(provider.connectionFields().stream().anyMatch(field -> field.type() == FieldType.SELECT));
        assertTrue(ServiceLoader.load(DatabaseProvider.class).iterator().hasNext());
    }

    private static Map<String, String> settings() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        result.put("host", "db.example"); result.put("port", "1521"); result.put("connectionMode", "service");
        result.put("service", "ORCLPDB1"); result.put("username", "app"); return result;
    }
    private static ConnectionProfile profile(Map<String, String> settings) {
        return new ConnectionProfile(UUID.randomUUID(), "oracle", "test", settings, "test");
    }
}
