package com.dbstudio.spi;

import java.util.Objects;

public final class ConnectionFieldOption {
    private final String value;
    private final String label;

    public ConnectionFieldOption(String value, String label) {
        this.value = Objects.requireNonNull(value, "value");
        this.label = Objects.requireNonNull(label, "label");
    }

    public String value() { return value; }
    public String label() { return label; }
}
