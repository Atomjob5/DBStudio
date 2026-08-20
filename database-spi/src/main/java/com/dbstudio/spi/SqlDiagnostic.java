package com.dbstudio.spi;

/** A dialect diagnostic whose offsets use Java/Monaco UTF-16 character units. */
public final class SqlDiagnostic {
    public static final String CATEGORY_SYNTAX = "syntax";
    public static final String CATEGORY_RISK = "risk";
    public static final String SEVERITY_ERROR = "error";
    public static final String SEVERITY_WARNING = "warning";

    private final String code;
    private final String category;
    private final String severity;
    private final String message;
    private final int startOffset;
    private final int endOffset;

    public SqlDiagnostic(String code, String category, String severity, String message,
                         int startOffset, int endOffset) {
        this.code = code == null ? "" : code;
        this.category = category == null ? "" : category;
        this.severity = severity == null ? "" : severity;
        this.message = message == null ? "" : message;
        this.startOffset = Math.max(0, startOffset);
        this.endOffset = Math.max(this.startOffset, endOffset);
    }

    public String code() { return code; }
    public String category() { return category; }
    public String severity() { return severity; }
    public String message() { return message; }
    public int startOffset() { return startOffset; }
    public int endOffset() { return endOffset; }
}
