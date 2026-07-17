package com.dbstudio.spi;

public final class SqlStatement {
    private final String text;
    private final int startOffset;
    private final int endOffset;
    private final StatementType type;

    public SqlStatement(String text, int startOffset, int endOffset, StatementType type) {
        this.text = text;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.type = type;
    }

    public String text() { return text; }
    public int startOffset() { return startOffset; }
    public int endOffset() { return endOffset; }
    public StatementType type() { return type; }
}
