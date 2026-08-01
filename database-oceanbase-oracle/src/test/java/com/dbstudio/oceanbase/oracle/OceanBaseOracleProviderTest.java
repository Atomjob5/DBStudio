package com.dbstudio.oceanbase.oracle;

import com.dbstudio.spi.ConnectionProfile;
import com.dbstudio.spi.DatabaseProvider;
import com.dbstudio.spi.contract.DatabaseProviderContract;
import com.dbstudio.spi.ResultEditPlan;
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

    @Test void reusesTheOracleRowIdEditContract() {
        DatabaseProvider provider = new OceanBaseOracleDatabaseProvider();
        ResultEditPlan plan = provider.dialect().resultEditPlan(
                "SELECT o.id, o.name AS display_name FROM app.orders o WHERE o.id > 0 FOR UPDATE NOWAIT");
        assertTrue(plan.editableCandidate());
        assertEquals("o", plan.source().get().alias());
        String rewritten = provider.dialect().appendResultLocatorColumns(
                "SELECT o.id FROM app.orders o FOR UPDATE",
                java.util.Collections.singletonList("ROWIDTOCHAR(o.ROWID)"),
                java.util.Collections.singletonList("DBSTUDIO_LOCATOR_0"));
        assertTrue(rewritten.contains("DBSTUDIO_LOCATOR_0"));
        assertTrue(rewritten.toUpperCase(java.util.Locale.ROOT).contains("FOR UPDATE"));
        String unqualifiedSql = "SELECT * FROM APP_CONFIG a WHERE a.id = 1 FOR UPDATE";
        ResultEditPlan unqualified = provider.dialect().resultEditPlan(unqualifiedSql);
        assertTrue(unqualified.editableCandidate());
        assertEquals("", unqualified.source().get().schema());
        assertEquals("a", provider.dialect().resultMutationQualifier(
                unqualifiedSql, unqualified.source().get()));
        String unqualifiedRewrite = provider.dialect().appendResultLocatorColumns(
                unqualifiedSql, java.util.Collections.singletonList("ROWIDTOCHAR(a.ROWID)"),
                java.util.Collections.singletonList("DBSTUDIO_LOCATOR_0"));
        assertTrue(unqualifiedRewrite.contains("a.*"));
        assertTrue(unqualifiedRewrite.contains("ROWIDTOCHAR(a.ROWID)"));
        assertEquals("DATABASE_LINK_NOT_SUPPORTED", provider.dialect().resultEditPlan(
                "SELECT id FROM app.orders@remote FOR UPDATE").reasonCode());
    }
}
