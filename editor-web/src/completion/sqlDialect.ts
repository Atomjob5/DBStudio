export interface SqlCompletionDialect {
  id: "mysql" | "oracle";
  keywords: readonly string[];
  statementKeywords: readonly string[];
  expressionKeywords: readonly string[];
  sourceKeywords: readonly string[];
  doubleQuoteIdentifiers: boolean;
  hashComments: boolean;
  backtickIdentifiers: boolean;
  backslashEscapes: boolean;
}

const STATEMENT_KEYWORDS = [
  "SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "ALTER", "DROP",
  "TRUNCATE", "EXPLAIN", "COMMIT", "ROLLBACK"
] as const;

const EXPRESSION_KEYWORDS = [
  "AND", "OR", "NOT", "NULL", "IS", "IN", "EXISTS", "BETWEEN", "LIKE", "CASE", "WHEN",
  "THEN", "ELSE", "END", "AS", "DISTINCT", "ALL", "COUNT", "SUM", "AVG", "MIN", "MAX",
  "COALESCE", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH", "RETURNING"
] as const;

const SOURCE_KEYWORDS = [
  "JOIN", "LEFT", "RIGHT", "FULL", "INNER", "OUTER", "CROSS", "ON", "WHERE", "GROUP", "ORDER",
  "HAVING", "UNION", "LIMIT", "OFFSET", "FETCH", "RETURNING"
] as const;

const MYSQL_KEYWORDS = unique([
  ...STATEMENT_KEYWORDS, ...EXPRESSION_KEYWORDS, ...SOURCE_KEYWORDS,
  "FROM", "INTO", "VALUES", "SET", "TABLE", "VIEW", "INDEX", "DATABASE", "SCHEMA", "BY",
  "RECURSIVE", "OVER", "PARTITION", "ROWS", "RANGE", "PROCEDURE", "FUNCTION", "TRIGGER",
  "BEGIN", "DECLARE", "IF", "ELSEIF", "WHILE", "LOOP", "RETURN", "CALL", "START", "TRANSACTION",
  "SHOW", "DESCRIBE", "NOW", "CURRENT_DATE", "CURRENT_TIMESTAMP", "IFNULL", "CONCAT",
  "JSON_EXTRACT", "ROW_NUMBER", "RANK", "USING"
]);

const ORACLE_KEYWORDS = unique([
  ...STATEMENT_KEYWORDS, ...EXPRESSION_KEYWORDS, ...SOURCE_KEYWORDS,
  "FROM", "INTO", "VALUES", "SET", "TABLE", "VIEW", "INDEX", "SEQUENCE", "SYNONYM", "BY",
  "OVER", "PARTITION", "ROWS", "RANGE", "PROCEDURE", "FUNCTION", "PACKAGE", "TRIGGER", "TYPE",
  "BEGIN", "DECLARE", "EXCEPTION", "LOOP", "RETURN", "CALL", "SAVEPOINT", "PLAN", "FOR", "USING",
  "SYSDATE", "SYSTIMESTAMP", "CURRENT_DATE", "CURRENT_TIMESTAMP", "NVL", "DECODE", "ROW_NUMBER", "RANK"
]);

const MYSQL: SqlCompletionDialect = {
  id: "mysql",
  keywords: MYSQL_KEYWORDS,
  statementKeywords: STATEMENT_KEYWORDS,
  expressionKeywords: EXPRESSION_KEYWORDS,
  sourceKeywords: SOURCE_KEYWORDS,
  doubleQuoteIdentifiers: false,
  hashComments: true,
  backtickIdentifiers: true,
  backslashEscapes: true
};

const ORACLE: SqlCompletionDialect = {
  id: "oracle",
  keywords: ORACLE_KEYWORDS,
  statementKeywords: STATEMENT_KEYWORDS,
  expressionKeywords: EXPRESSION_KEYWORDS,
  sourceKeywords: SOURCE_KEYWORDS,
  doubleQuoteIdentifiers: true,
  hashComments: false,
  backtickIdentifiers: false,
  backslashEscapes: false
};

export function completionDialect(providerId: string): SqlCompletionDialect {
  const normalized = providerId.toLocaleLowerCase();
  return normalized.includes("mysql") && !normalized.includes("oracle") ? MYSQL : ORACLE;
}

function unique(values: readonly string[]): readonly string[] {
  return [...new Set(values)];
}
