package com.dbstudio.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ConnectionProfile {
    private final UUID id;
    private final String providerId;
    private final String name;
    private final Map<String, String> settings;
    private final String secretRef;

    public ConnectionProfile(UUID id, String providerId, String name,
                             Map<String, String> settings, String secretRef) {
        this.id = Objects.requireNonNull(id, "id");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.name = Objects.requireNonNull(name, "name");
        this.settings = Collections.unmodifiableMap(
                new LinkedHashMap<String, String>(Objects.requireNonNull(settings, "settings")));
        this.secretRef = isBlank(secretRef) ? "dbstudio/" + id : secretRef;
    }

    public UUID id() { return id; }
    public String providerId() { return providerId; }
    public String name() { return name; }
    public Map<String, String> settings() { return settings; }
    public String secretRef() { return secretRef; }

    public String setting(String key) {
        String value = settings.get(key);
        return value == null ? "" : value;
    }

    public int intSetting(String key, int defaultValue) {
        String value = setting(key);
        if (isBlank(value)) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
