package com.dbstudio.spi;

/** Functional destination for an executed JDBC statement. */
public enum SqlLogCategory {
    BUSINESS("business"),
    RESULT_EDIT("result-edit"),
    METADATA("metadata"),
    TRANSFER("transfer"),
    CONNECTION("connection"),
    PERSISTENCE("persistence");

    private final String loggerSuffix;

    SqlLogCategory(String loggerSuffix) { this.loggerSuffix = loggerSuffix; }

    public String loggerSuffix() { return loggerSuffix; }
}
