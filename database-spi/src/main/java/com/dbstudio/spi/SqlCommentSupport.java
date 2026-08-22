package com.dbstudio.spi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Lexical comment handling shared by dialect splitters and current-statement lookup. */
public final class SqlCommentSupport {
    private SqlCommentSupport() { }

    public enum Dialect {
        MYSQL,
        ORACLE
    }

    /** Returns whether the text contains a non-comment SQL token. */
    public static boolean hasExecutableContent(String sql, Dialect dialect) {
        return scan(sql, dialect).executable;
    }

    /** Returns whether a UTF-16 offset falls inside an ordinary comment. */
    public static boolean isCommentOffset(String sql, int offset, Dialect dialect) {
        if (sql == null || sql.isEmpty()) return false;
        int cursor = Math.max(0, Math.min(offset, sql.length()));
        for (Range range : scan(sql, dialect).comments) {
            if (cursor >= range.start && cursor < range.end) return true;
        }
        return false;
    }

    /** Returns the physical line containing a UTF-16 offset and whether that line contains SQL. */
    public static CursorLine cursorLine(String sql, int offset, Dialect dialect) {
        if (sql == null || sql.isEmpty()) return new CursorLine(0, 0, false);
        int cursor = Math.max(0, Math.min(offset, sql.length()));
        int probe = cursor;
        if (probe > 0 && probe < sql.length() && sql.charAt(probe) == '\n'
                && sql.charAt(probe - 1) == '\r') {
            probe--;
        }
        int start = probe;
        while (start > 0 && sql.charAt(start - 1) != '\r' && sql.charAt(start - 1) != '\n') start--;
        int end = probe;
        while (end < sql.length() && sql.charAt(end) != '\r' && sql.charAt(end) != '\n') end++;

        List<Range> comments = scan(sql, dialect).comments;
        boolean executable = false;
        int commentIndex = 0;
        for (int index = start; index < end; index++) {
            while (commentIndex < comments.size() && comments.get(commentIndex).end <= index) commentIndex++;
            boolean comment = commentIndex < comments.size()
                    && index >= comments.get(commentIndex).start && index < comments.get(commentIndex).end;
            if (!comment && !Character.isWhitespace(sql.charAt(index))) {
                executable = true;
                break;
            }
        }
        return new CursorLine(start, end, executable);
    }

    /** Selects the complete statement associated with an executable cursor line. */
    public static Optional<SqlStatement> statementOnCursorLine(String sql, Dialect dialect,
                                                                List<SqlStatement> statements,
                                                                int cursorOffset, CursorLine line) {
        if (line == null || !line.hasExecutableContent() || statements == null || statements.isEmpty()) {
            return Optional.empty();
        }
        int cursor = Math.max(line.startOffset(), Math.min(cursorOffset, line.endOffset()));
        SqlStatement containing = null;
        for (SqlStatement statement : statements) {
            if (cursor >= statement.startOffset() && cursor <= statement.endOffset()) {
                containing = statement;
                break;
            }
        }

        SqlStatement preceding = null;
        SqlStatement following = null;
        for (SqlStatement statement : statements) {
            if (!intersects(statement, line)) continue;
            if (statement.endOffset() <= cursor) {
                if (preceding == null || statement.endOffset() > preceding.endOffset()
                        || (statement.endOffset() == preceding.endOffset()
                        && statement.startOffset() > preceding.startOffset())) {
                    preceding = statement;
                }
            } else if (following == null || statement.startOffset() < following.startOffset()) {
                following = statement;
            }
        }
        if (containing != null) {
            boolean comment = isCommentOffset(sql, cursor, dialect);
            int contentStart = Math.max(containing.startOffset(), line.startOffset());
            if (!comment || hasExecutableContent(sql, contentStart, cursor, dialect) || preceding == null) {
                return Optional.of(containing);
            }
        }
        return Optional.ofNullable(preceding == null ? following : preceding);
    }

    private static boolean hasExecutableContent(String sql, int rawStart, int rawEnd, Dialect dialect) {
        if (sql == null || sql.isEmpty()) return false;
        int start = Math.max(0, Math.min(rawStart, sql.length()));
        int end = Math.max(start, Math.min(rawEnd, sql.length()));
        List<Range> comments = scan(sql, dialect).comments;
        int commentIndex = 0;
        for (int index = start; index < end; index++) {
            while (commentIndex < comments.size() && comments.get(commentIndex).end <= index) commentIndex++;
            boolean comment = commentIndex < comments.size()
                    && index >= comments.get(commentIndex).start && index < comments.get(commentIndex).end;
            if (!comment && !Character.isWhitespace(sql.charAt(index))) return true;
        }
        return false;
    }

    private static boolean intersects(SqlStatement statement, CursorLine line) {
        return statement.startOffset() < line.endOffset() && statement.endOffset() > line.startOffset();
    }

    private static Scan scan(String sql, Dialect dialect) {
        if (sql == null || sql.isEmpty()) return new Scan(false, new ArrayList<Range>());
        List<Range> comments = new ArrayList<Range>();
        boolean executable = false;
        State state = State.NORMAL;
        int commentStart = -1;
        boolean executableBlockComment = false;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            switch (state) {
                case NORMAL:
                    if (current == '-' && next == '-' && isDashComment(sql, index + 2, dialect)) {
                        state = State.LINE_COMMENT;
                        commentStart = index;
                        index += 2;
                        continue;
                    }
                    if (dialect == Dialect.MYSQL && current == '#') {
                        state = State.LINE_COMMENT;
                        commentStart = index;
                        index++;
                        continue;
                    }
                    if (current == '/' && next == '*') {
                        executableBlockComment = dialect == Dialect.MYSQL
                                && index + 2 < sql.length()
                                && sql.charAt(index + 2) == '!';
                        if (executableBlockComment) executable = true;
                        state = State.BLOCK_COMMENT;
                        commentStart = index;
                        index += 2;
                        continue;
                    }
                    if (dialect == Dialect.ORACLE && (current == 'q' || current == 'Q')
                            && next == '\'' && index + 2 < sql.length()) {
                        executable = true;
                        int end = alternativeQuoteEnd(sql, index + 3,
                                pairedQuoteDelimiter(sql.charAt(index + 2)));
                        index = end < 0 ? sql.length() : end;
                        continue;
                    }
                    if (current == '\'' || current == '"'
                            || (dialect == Dialect.MYSQL && current == '`')) {
                        executable = true;
                        index = quotedEnd(sql, index, current, dialect);
                        continue;
                    }
                    if (!Character.isWhitespace(current)) executable = true;
                    index++;
                    continue;
                case LINE_COMMENT:
                    if (current == '\r' || current == '\n') {
                        comments.add(new Range(commentStart, index));
                        commentStart = -1;
                        state = State.NORMAL;
                        continue;
                    }
                    index++;
                    continue;
                case BLOCK_COMMENT:
                    if (current == '*' && next == '/') {
                        if (!executableBlockComment) comments.add(new Range(commentStart, index + 2));
                        commentStart = -1;
                        executableBlockComment = false;
                        state = State.NORMAL;
                        index += 2;
                        continue;
                    }
                    index++;
                    continue;
                default:
                    throw new IllegalStateException("未知 SQL 注释扫描状态");
            }
        }
        if (state == State.LINE_COMMENT || state == State.BLOCK_COMMENT) {
            if (!executableBlockComment) comments.add(new Range(commentStart, sql.length()));
        }
        return new Scan(executable, comments);
    }

    private static boolean isDashComment(String sql, int offset, Dialect dialect) {
        if (dialect == Dialect.ORACLE) return true;
        return offset >= sql.length() || Character.isWhitespace(sql.charAt(offset));
    }

    private static int quotedEnd(String sql, int start, char quote, Dialect dialect) {
        for (int index = start + 1; index < sql.length(); index++) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (dialect == Dialect.MYSQL && quote != '`' && current == '\\') {
                index++;
            } else if (current == quote && next == quote) {
                index++;
            } else if (current == quote) {
                return index + 1;
            }
        }
        return sql.length();
    }

    private static int alternativeQuoteEnd(String sql, int start, char closing) {
        for (int index = start; index + 1 < sql.length(); index++) {
            if (sql.charAt(index) == closing && sql.charAt(index + 1) == '\'') return index + 2;
        }
        return -1;
    }

    private static char pairedQuoteDelimiter(char opening) {
        return opening == '[' ? ']' : opening == '{' ? '}' : opening == '(' ? ')'
                : opening == '<' ? '>' : opening;
    }

    private enum State {
        NORMAL,
        LINE_COMMENT,
        BLOCK_COMMENT
    }

    private static final class Scan {
        private final boolean executable;
        private final List<Range> comments;

        private Scan(boolean executable, List<Range> comments) {
            this.executable = executable;
            this.comments = comments;
        }
    }

    private static final class Range {
        private final int start;
        private final int end;

        private Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    public static final class CursorLine {
        private final int startOffset;
        private final int endOffset;
        private final boolean executableContent;

        private CursorLine(int startOffset, int endOffset, boolean executableContent) {
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.executableContent = executableContent;
        }

        public int startOffset() { return startOffset; }
        public int endOffset() { return endOffset; }
        public boolean hasExecutableContent() { return executableContent; }
    }
}
