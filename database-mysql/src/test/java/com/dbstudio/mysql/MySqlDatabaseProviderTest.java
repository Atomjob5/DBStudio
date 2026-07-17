package com.dbstudio.mysql;

import com.dbstudio.spi.contract.DatabaseProviderContract;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlDatabaseProviderTest {
    @Test
    void satisfiesProviderDescriptorContract() {
        DatabaseProviderContract.verifyDescriptor(new MySqlDatabaseProvider());
    }

    @Test
    void isDiscoverableWithServiceLoader() {
        boolean found = false;
        for (com.dbstudio.spi.DatabaseProvider provider : ServiceLoader.load(com.dbstudio.spi.DatabaseProvider.class)) {
            if (provider.getClass() == MySqlDatabaseProvider.class) found = true;
        }
        assertTrue(found);
    }
}
