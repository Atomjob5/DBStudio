package com.dbstudio.desktop.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** SQL 日志摘要的安全回归测试，确保默认日志不会泄漏业务值或密码。 */
class SqlLogSupportTest {
    private final String previousMode = System.getProperty("dbstudio.logging.sql.mode");
    private final String previousLength = System.getProperty("dbstudio.logging.sql.preview-length");

    @AfterEach
    void restoreProperties() {
        restore("dbstudio.logging.sql.mode", previousMode);
        restore("dbstudio.logging.sql.preview-length", previousLength);
    }

    @Test
    void fingerprintIgnoresWhitespaceButSummaryDoesNotContainSqlText() {
        String first = "select * from orders where id = 7";
        String second = "select   *\nfrom orders where id = 7";
        assertTrue(SqlLogSupport.fingerprint(first).equals(SqlLogSupport.fingerprint(second)));
        String summary = SqlLogSupport.summary(first);
        assertFalse(summary.contains("orders"));
        assertFalse(summary.contains("id = 7"));
    }

    @Test
    void previewMasksLiteralsAndSecretAssignmentsAndHonorsLength() {
        System.setProperty("dbstudio.logging.sql.mode", "preview");
        System.setProperty("dbstudio.logging.sql.preview-length", "40");
        String preview = SqlLogSupport.preview("UPDATE account SET password='secret', name='Alice' WHERE id=1");
        assertFalse(preview.contains("secret"));
        assertFalse(preview.contains("Alice"));
        assertTrue(preview.length() <= 41);
    }

    @Test
    void summaryModeDoesNotReturnPreview() {
        System.setProperty("dbstudio.logging.sql.mode", "summary");
        assertTrue(SqlLogSupport.preview("SELECT 'private'").isEmpty());
        assertNotEquals("SELECT 'private'", SqlLogSupport.summary("SELECT 'private'"));
    }

    @Test
    void sanitizesSensitiveParametersInDriverMessages() {
        String safe = SqlLogSupport.sanitizeMessage(
                "connect failed password=secret token=abc Cookie: session-value");
        assertFalse(safe.contains("secret"));
        assertFalse(safe.contains("abc"));
        assertFalse(safe.contains("session-value"));
        assertTrue(safe.contains("password=***"));
        assertTrue(safe.contains("token=***"));
        assertTrue(safe.contains("Cookie: ***"));
    }

    @Test
    void fullModeKeepsExplicitPreviewUntruncatedButStillMasksSecrets() {
        System.setProperty("dbstudio.logging.sql.mode", "full");
        String sql = "select 'business-value' from orders where token=secret-token";
        String preview = SqlLogSupport.preview(sql);
        assertTrue(preview.contains("business-value"));
        assertFalse(preview.contains("secret-token"));
        assertTrue(preview.contains("token=***"));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
