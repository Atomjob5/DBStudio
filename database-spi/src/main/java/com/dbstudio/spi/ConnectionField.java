package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ConnectionField {
    private final String key;
    private final String label;
    private final FieldType type;
    private final boolean required;
    private final String defaultValue;
    private final String description;
    private final List<ConnectionFieldOption> options;

    public ConnectionField(String key, String label, FieldType type, boolean required,
                           String defaultValue, String description) {
        this(key, label, type, required, defaultValue, description,
                Collections.<ConnectionFieldOption>emptyList());
    }

    public ConnectionField(String key, String label, FieldType type, boolean required,
                           String defaultValue, String description,
                           List<ConnectionFieldOption> options) {
        this.key = Objects.requireNonNull(key, "key");
        this.label = Objects.requireNonNull(label, "label");
        this.type = Objects.requireNonNull(type, "type");
        this.required = required;
        this.defaultValue = defaultValue == null ? "" : defaultValue;
        this.description = description == null ? "" : description;
        this.options = options == null ? Collections.<ConnectionFieldOption>emptyList()
                : Collections.unmodifiableList(new ArrayList<ConnectionFieldOption>(options));
    }

    public String key() { return key; }
    public String label() { return label; }
    public FieldType type() { return type; }
    public boolean required() { return required; }
    public String defaultValue() { return defaultValue; }
    public String description() { return description; }
    public List<ConnectionFieldOption> options() { return options; }
}
