package com.dbstudio.spi;

import java.util.Objects;

public final class ConnectionField {
    private final String key;
    private final String label;
    private final FieldType type;
    private final boolean required;
    private final String defaultValue;
    private final String description;

    public ConnectionField(String key, String label, FieldType type, boolean required,
                           String defaultValue, String description) {
        this.key = Objects.requireNonNull(key, "key");
        this.label = Objects.requireNonNull(label, "label");
        this.type = Objects.requireNonNull(type, "type");
        this.required = required;
        this.defaultValue = defaultValue == null ? "" : defaultValue;
        this.description = description == null ? "" : description;
    }

    public String key() { return key; }
    public String label() { return label; }
    public FieldType type() { return type; }
    public boolean required() { return required; }
    public String defaultValue() { return defaultValue; }
    public String description() { return description; }
}
