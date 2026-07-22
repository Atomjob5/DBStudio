package com.dbstudio.desktop.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 日志安全工具。
 *
 * <p>查询文本可能包含业务数据、密码或令牌，因此默认只输出语句指纹和统计信息。
 * 只有显式开启 preview/full 模式并且日志级别为 DEBUG 时，才会输出经过截断和字面量
 * 脱敏的文本。</p>
 */
public final class SqlLogSupport {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|token|secret|access_token)\\s*=\\s*([^\\s,&;]+)");
    private static final Pattern SENSITIVE_PARAMETER = Pattern.compile(
            "(?i)(password|passwd|token|secret|access_token|cookie)(\\s*[:=]\\s*)[^\\s,;&]+");

    private SqlLogSupport() { }

    /** 返回稳定的 SHA-256 指纹，不把原始 SQL 写入日志。 */
    public static String fingerprint(String sql) {
        String normalized = normalize(sql);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK缺少SHA-256实现", exception);
        }
    }

    /** 返回不包含 SQL 正文的安全摘要。 */
    public static String summary(String sql) {
        String text = normalize(sql);
        String type = firstKeyword(text);
        return "type=" + type + ", chars=" + text.length() + ", fingerprint=" + fingerprint(text);
    }

    /**
     * 按配置生成可选的 SQL 预览；summary 模式返回空字符串。
     * 预览会遮蔽字符串字面量和常见密钥参数，且有固定长度上限。
     */
    public static String preview(String sql) {
        String mode = System.getProperty("dbstudio.logging.sql.mode", "summary").trim().toLowerCase(Locale.ROOT);
        if ("summary".equals(mode)) return "";
        String normalized = normalize(sql);
        String safe;
        if ("full".equals(mode)) {
            // full 也必须遮蔽密码、令牌等敏感参数，但允许显式配置者查看完整语句结构。
            safe = SENSITIVE_PARAMETER.matcher(normalized).replaceAll("$1$2***");
        } else {
            // preview 额外遮蔽普通字符串字面量，防止业务数据进入调试日志。
            safe = SECRET_ASSIGNMENT.matcher(STRING_LITERAL.matcher(normalized).replaceAll("'***'"))
                    .replaceAll("$1=***");
        }
        int limit;
        try { limit = Integer.parseInt(System.getProperty("dbstudio.logging.sql.preview-length", "300")); }
        catch (NumberFormatException exception) { limit = 300; }
        limit = Math.max(32, Math.min(2_000, limit));
        if ("full".equals(mode)) return safe;
        if (safe.length() <= limit) return safe;
        return safe.substring(0, limit) + "…";
    }

    /** 脱敏异常或驱动错误文本中的密码、令牌、Cookie 等参数，避免错误信息反向泄漏凭据。 */
    public static String sanitizeMessage(String message) {
        if (message == null || message.trim().isEmpty()) return "未知错误";
        String safe = SECRET_ASSIGNMENT.matcher(message).replaceAll("$1=***");
        return SENSITIVE_PARAMETER.matcher(safe).replaceAll("$1$2***");
    }

    /** 将空白压缩，保证同一语句的指纹不受格式换行影响。 */
    public static String normalize(String sql) {
        return WHITESPACE.matcher(sql == null ? "" : sql.trim()).replaceAll(" ");
    }

    private static String firstKeyword(String sql) {
        int separator = sql.indexOf(' ');
        String keyword = separator < 0 ? sql : sql.substring(0, separator);
        return keyword.isEmpty() ? "EMPTY" : keyword.toUpperCase(Locale.ROOT);
    }
}
