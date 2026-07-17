package com.dbstudio.spi;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public final class DatabaseCapabilities {
    private final Set<DatabaseCapability> values;

    public DatabaseCapabilities(Set<DatabaseCapability> values) {
        this.values = values == null || values.isEmpty()
                ? Collections.<DatabaseCapability>emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(values));
    }

    public Set<DatabaseCapability> values() { return values; }

    public static DatabaseCapabilities of(DatabaseCapability... capabilities) {
        EnumSet<DatabaseCapability> result = EnumSet.noneOf(DatabaseCapability.class);
        if (capabilities != null) {
            for (DatabaseCapability capability : capabilities) {
                if (capability != null) result.add(capability);
            }
        }
        return new DatabaseCapabilities(result);
    }

    public boolean supports(DatabaseCapability capability) {
        return values.contains(capability);
    }
}
