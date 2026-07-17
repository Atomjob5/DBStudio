package com.dbstudio.spi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface SqlDialect {
    String id();

    String quoteIdentifier(String identifier);

    Set<String> keywords();

    List<SqlStatement> split(String script);

    Optional<SqlStatement> currentStatement(String script, int cursorOffset);

    StatementType classify(String sql);

    String format(String sql);
}
