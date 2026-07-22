package com.dbstudio.spi;

public enum TransactionEffect {
    NONE,
    DIRTY,
    END,
    IMPLICIT_COMMIT
}
