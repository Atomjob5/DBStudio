package com.dbstudio.desktop.security;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class MemorySecretStore implements SecretStore, AutoCloseable {
    private final Map<String, char[]> values = new ConcurrentHashMap<String, char[]>();

    @Override
    public void save(String reference, char[] secret) {
        char[] previous = values.put(reference, Arrays.copyOf(secret, secret.length));
        clear(previous);
    }

    @Override
    public Optional<char[]> load(String reference) {
        char[] value = values.get(reference);
        return value == null ? Optional.<char[]>empty() : Optional.of(Arrays.copyOf(value, value.length));
    }

    @Override
    public void delete(String reference) {
        clear(values.remove(reference));
    }

    @Override
    public void close() {
        values.values().forEach(MemorySecretStore::clear);
        values.clear();
    }

    private static void clear(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }
}
