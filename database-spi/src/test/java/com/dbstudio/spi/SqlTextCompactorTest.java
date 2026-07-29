package com.dbstudio.spi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlTextCompactorTest {
    @Test
    void fallsBackToWhitespaceCompactionWhenACommentWasDropped() {
        String source = "select q'[a  -- not a comment]' as value /* keep block */\n"
                + "from dual -- keep line\nwhere id = 1";

        String compact = SqlTextCompactor.preserveComments(source, "SELECT q'[a  -- not a comment]' AS value FROM dual WHERE id = 1");

        assertEquals("select q'[a  -- not a comment]' as value /* keep block */ from dual -- keep line\n"
                + "where id = 1", compact);
    }

    @Test
    void keepsDialectOutputWhenItContainsEveryComment() {
        String compacted = "SELECT 1 /* keep */";
        assertEquals(compacted, SqlTextCompactor.preserveComments("select 1 /* keep */", compacted));
    }

    @Test
    void doesNotMistakeStringContentForACommentOrLoseDuplicateComments() {
        String source = "select '/* keep */' /* keep */, 1 /* keep */ from dual";
        String formatterDroppedOneComment = "SELECT '/* keep */' /* keep */, 1 FROM dual";

        assertEquals(source, SqlTextCompactor.preserveComments(source, formatterDroppedOneComment));
    }
}
