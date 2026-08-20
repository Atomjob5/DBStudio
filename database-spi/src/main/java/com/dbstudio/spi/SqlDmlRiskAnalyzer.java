package com.dbstudio.spi;

import java.util.Locale;

/** Lightweight fallback for dialect parsers that cannot expose a WITH-prefixed DML root node. */
public final class SqlDmlRiskAnalyzer {
    private SqlDmlRiskAnalyzer() {
    }

    public static boolean requiresWhereClauseConfirmation(String sql) {
        RiskAssessment assessment = assessRisk(sql);
        return assessment.dmlOffset >= 0 && !assessment.hasWhereClause;
    }

    /** Returns the UTF-16 offset of the risky top-level UPDATE/DELETE keyword, or -1. */
    public static int riskyDmlKeywordOffset(String sql) {
        RiskAssessment assessment = assessRisk(sql);
        return assessment.dmlOffset >= 0 && !assessment.hasWhereClause ? assessment.dmlOffset : -1;
    }

    private static RiskAssessment assessRisk(String sql) {
        if (sql == null || sql.trim().isEmpty()) return RiskAssessment.safe();
        String firstTopLevelWord = null;
        int riskyDmlOffset = -1;
        int depth = 0;
        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (current == '-' && next == '-') {
                index = lineCommentEnd(sql, index + 2);
                continue;
            }
            if (current == '#') {
                index = lineCommentEnd(sql, index + 1);
                continue;
            }
            if (current == '/' && next == '*') {
                int end = sql.indexOf("*/", index + 2);
                index = end < 0 ? sql.length() : end + 2;
                continue;
            }
            if ((current == 'q' || current == 'Q') && next == '\'' && index + 2 < sql.length()) {
                index = oracleQuotedEnd(sql, index);
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                index = quotedEnd(sql, index, current);
                continue;
            }
            if (current == '(') {
                depth++;
                index++;
                continue;
            }
            if (current == ')') {
                if (depth > 0) depth--;
                index++;
                continue;
            }
            if (!isWordStart(current)) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < sql.length() && isWordPart(sql.charAt(end))) end++;
            if (depth == 0) {
                String word = sql.substring(index, end).toUpperCase(Locale.ROOT);
                if (firstTopLevelWord == null) {
                    firstTopLevelWord = word;
                    if ("UPDATE".equals(word) || "DELETE".equals(word)) riskyDmlOffset = index;
                    else if (!"WITH".equals(word)) return RiskAssessment.safe();
                } else if ("WITH".equals(firstTopLevelWord) && riskyDmlOffset < 0
                        && ("UPDATE".equals(word) || "DELETE".equals(word))) {
                    riskyDmlOffset = index;
                } else if (riskyDmlOffset >= 0 && "WHERE".equals(word)) {
                    return new RiskAssessment(riskyDmlOffset, true);
                }
            }
            index = end;
        }
        return new RiskAssessment(riskyDmlOffset, false);
    }

    /** Returns whether a top-level SELECT carries a locking FOR UPDATE clause. */
    public static boolean hasTopLevelForUpdate(String sql) {
        if (sql == null || sql.trim().isEmpty()) return false;
        String previousTopLevelWord = null;
        int depth = 0;
        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (current == '-' && next == '-') { index = lineCommentEnd(sql, index + 2); continue; }
            if (current == '#') { index = lineCommentEnd(sql, index + 1); continue; }
            if (current == '/' && next == '*') {
                int end = sql.indexOf("*/", index + 2);
                index = end < 0 ? sql.length() : end + 2;
                continue;
            }
            if ((current == 'q' || current == 'Q') && next == '\'' && index + 2 < sql.length()) {
                index = oracleQuotedEnd(sql, index);
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') { index = quotedEnd(sql, index, current); continue; }
            if (current == '(') { depth++; index++; continue; }
            if (current == ')') { if (depth > 0) depth--; index++; continue; }
            if (!isWordStart(current)) { index++; continue; }
            int end = index + 1;
            while (end < sql.length() && isWordPart(sql.charAt(end))) end++;
            if (depth == 0) {
                String word = sql.substring(index, end).toUpperCase(Locale.ROOT);
                if ("UPDATE".equals(word) && "FOR".equals(previousTopLevelWord)) return true;
                previousTopLevelWord = word;
            }
            index = end;
        }
        return false;
    }

    /**
     * Accepts a plain SELECT or a SELECT rooted by a WITH clause.  Dialects classify commands such
     * as SHOW and EXPLAIN as queries as well, but the temporary-result action deliberately allows
     * only read-only SELECT statements.
     */
    public static boolean isTopLevelReadOnlySelect(String sql) {
        if (sql == null || sql.trim().isEmpty()) return false;
        boolean withClause = false;
        int depth = 0;
        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (current == '-' && next == '-') { index = lineCommentEnd(sql, index + 2); continue; }
            if (current == '#') { index = lineCommentEnd(sql, index + 1); continue; }
            if (current == '/' && next == '*') {
                int end = sql.indexOf("*/", index + 2);
                index = end < 0 ? sql.length() : end + 2;
                continue;
            }
            if ((current == 'q' || current == 'Q') && next == '\'' && index + 2 < sql.length()) {
                index = oracleQuotedEnd(sql, index);
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') { index = quotedEnd(sql, index, current); continue; }
            if (current == '(') { depth++; index++; continue; }
            if (current == ')') { if (depth > 0) depth--; index++; continue; }
            if (!isWordStart(current)) { index++; continue; }
            int end = index + 1;
            while (end < sql.length() && isWordPart(sql.charAt(end))) end++;
            if (depth == 0) {
                String word = sql.substring(index, end).toUpperCase(Locale.ROOT);
                if (!withClause) {
                    if ("SELECT".equals(word)) return true;
                    if (!"WITH".equals(word)) return false;
                    withClause = true;
                } else if ("SELECT".equals(word)) {
                    return true;
                } else if ("INSERT".equals(word) || "UPDATE".equals(word)
                        || "DELETE".equals(word) || "MERGE".equals(word)) {
                    return false;
                }
            }
            index = end;
        }
        return false;
    }

    private static boolean isWordStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isWordPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$' || value == '#';
    }

    private static int lineCommentEnd(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\r' && sql.charAt(index) != '\n') index++;
        return index;
    }

    private static int quotedEnd(String sql, int index, char quote) {
        for (int cursor = index + 1; cursor < sql.length(); cursor++) {
            char current = sql.charAt(cursor);
            if (current == '\\' && cursor + 1 < sql.length()) {
                cursor++;
                continue;
            }
            if (current != quote) continue;
            if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == quote) {
                cursor++;
                continue;
            }
            return cursor + 1;
        }
        return sql.length();
    }

    private static int oracleQuotedEnd(String sql, int index) {
        char opening = sql.charAt(index + 2);
        char closing = opening == '[' ? ']' : opening == '{' ? '}' : opening == '(' ? ')'
                : opening == '<' ? '>' : opening;
        for (int cursor = index + 3; cursor + 1 < sql.length(); cursor++) {
            if (sql.charAt(cursor) == closing && sql.charAt(cursor + 1) == '\'') return cursor + 2;
        }
        return sql.length();
    }

    private static final class RiskAssessment {
        private final int dmlOffset;
        private final boolean hasWhereClause;

        private RiskAssessment(int dmlOffset, boolean hasWhereClause) {
            this.dmlOffset = dmlOffset;
            this.hasWhereClause = hasWhereClause;
        }

        private static RiskAssessment safe() { return new RiskAssessment(-1, false); }
    }
}
