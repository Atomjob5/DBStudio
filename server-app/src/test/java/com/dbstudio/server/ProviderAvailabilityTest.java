package com.dbstudio.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dbstudio.desktop.ProviderRegistry;
import com.dbstudio.spi.DatabaseProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ProviderAvailabilityTest {
    @Test
    void runtimeClasspathProvidesMysqlOracleAndOceanBaseOracle() {
        List<String> ids = new ArrayList<String>();
        for (DatabaseProvider provider : new ProviderRegistry().all()) ids.add(provider.id());
        Collections.sort(ids);
        assertEquals(java.util.Arrays.asList("mysql", "oceanbase-oracle", "oracle"), ids);
    }
}
