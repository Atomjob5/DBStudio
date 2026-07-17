package com.dbstudio.spi.contract;

import com.dbstudio.spi.DatabaseProvider;
import java.util.HashSet;
import org.junit.jupiter.api.Assertions;

public final class DatabaseProviderContract {
    private DatabaseProviderContract() {
    }

    public static void verifyDescriptor(DatabaseProvider provider) {
        Assertions.assertNotNull(provider);
        Assertions.assertFalse(provider.id().trim().isEmpty());
        Assertions.assertFalse(provider.displayName().trim().isEmpty());
        Assertions.assertNotNull(provider.connections());
        Assertions.assertNotNull(provider.metadata());
        Assertions.assertNotNull(provider.dialect());
        Assertions.assertNotNull(provider.capabilities());
        Assertions.assertFalse(provider.connectionFields().isEmpty());

        final HashSet<String> keys = new HashSet<String>();
        provider.connectionFields().forEach(field ->
                Assertions.assertTrue(keys.add(field.key()), "Duplicate connection field: " + field.key()));
    }
}
