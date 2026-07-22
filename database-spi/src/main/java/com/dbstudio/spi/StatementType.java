package com.dbstudio.spi;

public enum StatementType {
    QUERY,
    INSERT,
    UPDATE,
    DELETE,
    DDL,
    TRANSACTION,
    OTHER;

    public boolean modifiesData() {
        return this == INSERT || this == UPDATE || this == DELETE;
    }

}
