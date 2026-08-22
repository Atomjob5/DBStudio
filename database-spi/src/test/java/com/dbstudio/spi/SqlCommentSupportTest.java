package com.dbstudio.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCommentSupportTest {
    @Test
    void distinguishesCommentsFromExecutableContentForEachDialect() {
        assertFalse(SqlCommentSupport.hasExecutableContent("-- note\n/* block */", SqlCommentSupport.Dialect.MYSQL));
        assertFalse(SqlCommentSupport.hasExecutableContent("# note\n/* block */", SqlCommentSupport.Dialect.MYSQL));
        assertFalse(SqlCommentSupport.hasExecutableContent("-- note\n/* block */", SqlCommentSupport.Dialect.ORACLE));
        assertTrue(SqlCommentSupport.hasExecutableContent("# identifier", SqlCommentSupport.Dialect.ORACLE));
        assertTrue(SqlCommentSupport.hasExecutableContent("SELECT '-- note'", SqlCommentSupport.Dialect.MYSQL));
        assertTrue(SqlCommentSupport.hasExecutableContent("/*!40101 SET @x=1 */", SqlCommentSupport.Dialect.MYSQL));
    }

    @Test
    void recognizesCommentOffsetsWithoutTreatingCommentMarkersInsideStringsAsComments() {
        String sql = "-- leading\nSELECT '/* text */', `# text` /* inline */ FROM orders";
        int leading = sql.indexOf("leading");
        int inline = sql.indexOf("inline");
        int string = sql.indexOf("/* text */");

        assertTrue(SqlCommentSupport.isCommentOffset(sql, leading, SqlCommentSupport.Dialect.MYSQL));
        assertTrue(SqlCommentSupport.isCommentOffset(sql, inline, SqlCommentSupport.Dialect.MYSQL));
        assertFalse(SqlCommentSupport.isCommentOffset(sql, string, SqlCommentSupport.Dialect.MYSQL));
        assertFalse(SqlCommentSupport.isCommentOffset(sql, sql.indexOf("SELECT"), SqlCommentSupport.Dialect.MYSQL));
    }

    @Test
    void analyzesExecutableContentOnThePhysicalCursorLineInUtf16Offsets() {
        String sql = "-- 注释😀\r\nSELECT /*+ parallel(8) */ '/* text */'; /* 尾部 */\r\n"
                + "/* 多行\r\n注释 */\r\n\r\n/*!40101 SET @x=1 */";

        SqlCommentSupport.CursorLine comment = SqlCommentSupport.cursorLine(
                sql, sql.indexOf("😀"), SqlCommentSupport.Dialect.MYSQL);
        assertEquals(0, comment.startOffset());
        assertEquals(sql.indexOf('\r'), comment.endOffset());
        assertFalse(comment.hasExecutableContent());

        assertTrue(SqlCommentSupport.cursorLine(sql, sql.indexOf("parallel"),
                SqlCommentSupport.Dialect.MYSQL).hasExecutableContent());
        assertFalse(SqlCommentSupport.cursorLine(sql, sql.indexOf("注释 */"),
                SqlCommentSupport.Dialect.MYSQL).hasExecutableContent());
        assertFalse(SqlCommentSupport.cursorLine(sql, sql.indexOf("\r\n\r\n") + 2,
                SqlCommentSupport.Dialect.MYSQL).hasExecutableContent());
        assertTrue(SqlCommentSupport.cursorLine(sql, sql.indexOf("40101"),
                SqlCommentSupport.Dialect.MYSQL).hasExecutableContent());
        assertFalse(SqlCommentSupport.cursorLine("/*! ordinary in Oracle */", 5,
                SqlCommentSupport.Dialect.ORACLE).hasExecutableContent());
    }
}
