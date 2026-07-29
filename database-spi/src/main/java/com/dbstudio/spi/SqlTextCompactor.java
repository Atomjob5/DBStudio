package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.List;

/** SQL 文本压缩辅助：当方言格式化器遗漏注释时，保留原词法内容并仅收拢外部空白。 */
public final class SqlTextCompactor {
    private SqlTextCompactor() {
    }

    public static String preserveComments(String source, String compacted) {
        if (source == null || source.isEmpty()) return compacted;
        String result = compacted == null ? "" : compacted;
        return comments(source).equals(comments(result)) ? result : compactWhitespace(source);
    }

    private static List<String> comments(String sql) {
        List<String> result = new ArrayList<String>();
        for (int index = 0; index < sql.length();) {
            int quotedEnd = quotedEnd(sql, index);
            if (quotedEnd > index) {
                index = quotedEnd;
                continue;
            }
            int commentEnd = commentEnd(sql, index);
            if (commentEnd > index) {
                result.add(sql.substring(index, commentEnd));
                index = commentEnd;
                continue;
            }
            index++;
        }
        return result;
    }

    private static String compactWhitespace(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        boolean whitespace = false;
        for (int index = 0; index < sql.length();) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                whitespace = true;
                index++;
                continue;
            }
            if (whitespace && result.length() > 0 && result.charAt(result.length() - 1) != '\n') {
                result.append(' ');
            }
            whitespace = false;
            int quotedEnd = quotedEnd(sql, index);
            if (quotedEnd > index) {
                result.append(sql, index, quotedEnd);
                index = quotedEnd;
                continue;
            }
            int commentEnd = commentEnd(sql, index);
            if (commentEnd > index) {
                boolean lineComment = sql.startsWith("--", index) || sql.charAt(index) == '#';
                result.append(sql, index, commentEnd);
                index = commentEnd;
                if (lineComment && index < sql.length()) {
                    result.append('\n');
                    while (index < sql.length() && (sql.charAt(index) == '\r' || sql.charAt(index) == '\n')) {
                        index++;
                    }
                }
                continue;
            }
            result.append(current);
            index++;
        }
        while (result.length() > 0 && Character.isWhitespace(result.charAt(result.length() - 1))) {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    private static int quotedEnd(String sql, int index) {
        char current = sql.charAt(index);
        if ((current == 'q' || current == 'Q') && index + 2 < sql.length()
                && sql.charAt(index + 1) == '\'') {
            char opening = sql.charAt(index + 2);
            char closing = opening == '[' ? ']' : opening == '{' ? '}' : opening == '(' ? ')'
                    : opening == '<' ? '>' : opening;
            for (int cursor = index + 3; cursor + 1 < sql.length(); cursor++) {
                if (sql.charAt(cursor) == closing && sql.charAt(cursor + 1) == '\'') return cursor + 2;
            }
            return sql.length();
        }
        if (current != '\'' && current != '"' && current != '`') return index;
        for (int cursor = index + 1; cursor < sql.length(); cursor++) {
            char candidate = sql.charAt(cursor);
            if (candidate == '\\' && cursor + 1 < sql.length()) {
                cursor++;
                continue;
            }
            if (candidate != current) continue;
            if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == current) {
                cursor++;
                continue;
            }
            return cursor + 1;
        }
        return sql.length();
    }

    private static int commentEnd(String sql, int index) {
        if (sql.startsWith("/*", index)) {
            int end = sql.indexOf("*/", index + 2);
            return end < 0 ? sql.length() : end + 2;
        }
        if (sql.startsWith("--", index) || sql.charAt(index) == '#') {
            int end = index + (sql.charAt(index) == '#' ? 1 : 2);
            while (end < sql.length() && sql.charAt(end) != '\r' && sql.charAt(end) != '\n') end++;
            return end;
        }
        return index;
    }
}
