package com.dbstudio.spi;

public final class ObjectIndexColumnInfo {
    private final String name;
    private final String expression;
    private final String direction;
    private final int ordinal;

    public ObjectIndexColumnInfo(String name, String expression, String direction, int ordinal) {
        this.name = name == null ? "" : name;
        this.expression = expression == null ? "" : expression;
        this.direction = direction == null ? "" : direction;
        this.ordinal = ordinal;
    }
    public String name() { return name; }
    public String expression() { return expression; }
    public String direction() { return direction; }
    public int ordinal() { return ordinal; }
}
