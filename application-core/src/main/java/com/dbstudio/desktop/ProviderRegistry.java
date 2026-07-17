package com.dbstudio.desktop;

import com.dbstudio.spi.DatabaseProvider;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class ProviderRegistry {
    private final Map<String, DatabaseProvider> providers;

    public ProviderRegistry() {
        final Map<String, DatabaseProvider> discovered = new LinkedHashMap<String, DatabaseProvider>();
        for (DatabaseProvider provider : ServiceLoader.load(DatabaseProvider.class)) {
            DatabaseProvider previous = discovered.put(provider.id(), provider);
            if (previous != null) {
                throw new IllegalStateException("Duplicate database provider: " + provider.id());
            }
        }
        if (discovered.isEmpty()) {
            throw new IllegalStateException("No database providers were found on the runtime classpath");
        }
        providers = Collections.unmodifiableMap(new LinkedHashMap<String, DatabaseProvider>(discovered));
    }

    public List<DatabaseProvider> all() {
        return Collections.unmodifiableList(new ArrayList<DatabaseProvider>(providers.values()));
    }

    public DatabaseProvider require(String id) {
        DatabaseProvider provider = providers.get(id);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown database provider: " + id);
        }
        return provider;
    }
}
