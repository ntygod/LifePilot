package com.lifepilot.meta.infra.browser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 浏览器文本快照清洗器 — 移除 JS/CSS/全局状态等无语义噪声，保留页面核心内容。
 *
 * <p>清洗规则按 14 步管线顺序执行：
 * <ol>
 *   <li>移除 {@code <script>} 标签及其内容</li>
 *   <li>移除 {@code <style>} 标签及其内容</li>
 *   <li>移除 {@code <noscript>} 标签及其内容</li>
 *   <li>移除 {@code <svg>} 标签及其内容</li>
 *   <li>移除 HTML 注释</li>
 *   <li>移除 {@code window.__pinia} 等全局状态注入行</li>
 *   <li>移除 CSS 资源 URL 行</li>
 *   <li>移除嵌套 JSON 配置块</li>
 *   <li>移除 Base64 data URI</li>
 *   <li>移除 data-* 属性</li>
 *   <li>移除内联 style 属性</li>
 *   <li>移除 aria-&#42;/role 属性</li>
 *   <li>压缩连续空行为单个空行</li>
 *   <li>strip + 截断</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-15
 */
public class TextSnapshotCleaner {

    private static final Logger log = LoggerFactory.getLogger(TextSnapshotCleaner.class);

    // ===== 整块标签移除（步骤 1-4） =====

    /** 步骤 1：匹配 script 标签及内容（含多行）。 */
    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "<script[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);

    /** 步骤 2：匹配 style 标签及内容（含多行）。 */
    private static final Pattern STYLE_TAG = Pattern.compile(
            "<style[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);

    /** 步骤 3：匹配 noscript 标签及内容（含多行）。 */
    private static final Pattern NOSCRIPT_TAG = Pattern.compile(
            "<noscript[^>]*>[\\s\\S]*?</noscript>", Pattern.CASE_INSENSITIVE);

    /** 步骤 4：匹配 svg 标签及内容（含多行）。 */
    private static final Pattern SVG_TAG = Pattern.compile(
            "<svg[^>]*>[\\s\\S]*?</svg>", Pattern.CASE_INSENSITIVE);

    // ===== 注释与行级噪声（步骤 5-8） =====

    /** 步骤 5：匹配 HTML 注释（含多行）。 */
    private static final Pattern HTML_COMMENT = Pattern.compile(
            "<!--[\\s\\S]*?-->");

    /** 步骤 6：匹配全局状态注入行（window.__pinia、window.__INITIAL_STATE__ 等）。 */
    private static final Pattern GLOBAL_STATE = Pattern.compile(
            "(?m)^.*window\\.__[a-zA-Z_]+.*$");

    /** 步骤 7：匹配 CSS 资源 URL 行。 */
    private static final Pattern CSS_URL_LINE = Pattern.compile(
            "(?m)^.*(?:url\\(data:|https?://[^\\s]*\\.css).*$");

    /** 步骤 8：匹配以 {" 开头且长度超过 200 字符的 JSON 配置行。 */
    private static final Pattern JSON_CONFIG_LINE = Pattern.compile(
            "(?m)^\\s*\\{\"[^\\n]{200,}$");

    // ===== 内联噪声属性（步骤 9-12） =====

    /** 步骤 9：匹配 Base64 data URI。 */
    private static final Pattern BASE64_DATA_URI = Pattern.compile(
            "data:[a-zA-Z/+]+;base64,[A-Za-z0-9+/=]+");

    /** 步骤 10：匹配 data-* 属性。 */
    private static final Pattern DATA_ATTRIBUTE = Pattern.compile(
            "\\s+data-[\\w-]+=\"[^\"]*\"");

    /** 步骤 11：匹配内联 style 属性。 */
    private static final Pattern INLINE_STYLE = Pattern.compile(
            "\\s+style=\"[^\"]*\"");

    /** 步骤 12：匹配 aria 和 role 属性。 */
    private static final Pattern ARIA_ROLE_ATTR = Pattern.compile(
            "\\s+(?:aria-[\\w-]+|role)=\"[^\"]*\"");

    // ===== 空行压缩（步骤 13） =====

    /** 步骤 13：压缩连续空行（3 个及以上换行）为双换行。 */
    private static final Pattern CONSECUTIVE_BLANK_LINES = Pattern.compile(
            "\\n{3,}");

    private final int maxLength;

    /**
     * 构造清洗器。
     *
     * @param maxLength 最大输出长度，超出部分截断并追加提示
     */
    public TextSnapshotCleaner(int maxLength) {
        this.maxLength = maxLength;
    }

    /**
     * 清洗文本快照，移除噪声内容并截断。
     *
     * @param rawSnapshot 原始文本快照，可为 null
     * @return 清洗后的文本，null 输入返回空字符串
     */
    public String clean(String rawSnapshot) {
        if (rawSnapshot == null || rawSnapshot.isBlank()) {
            return "";
        }

        int originalLength = rawSnapshot.length();
        String result = rawSnapshot;

        // 14 步清洗管线
        result = SCRIPT_TAG.matcher(result).replaceAll("");           // 1
        result = STYLE_TAG.matcher(result).replaceAll("");            // 2
        result = NOSCRIPT_TAG.matcher(result).replaceAll("");         // 3
        result = SVG_TAG.matcher(result).replaceAll("");              // 4
        result = HTML_COMMENT.matcher(result).replaceAll("");         // 5
        result = GLOBAL_STATE.matcher(result).replaceAll("");         // 6
        result = CSS_URL_LINE.matcher(result).replaceAll("");         // 7
        result = JSON_CONFIG_LINE.matcher(result).replaceAll("");     // 8
        result = BASE64_DATA_URI.matcher(result).replaceAll("");      // 9
        result = DATA_ATTRIBUTE.matcher(result).replaceAll("");       // 10
        result = INLINE_STYLE.matcher(result).replaceAll("");         // 11
        result = ARIA_ROLE_ATTR.matcher(result).replaceAll("");       // 12
        result = CONSECUTIVE_BLANK_LINES.matcher(result).replaceAll("\n\n"); // 13
        result = result.strip();                                      // 14

        int cleanedLength = result.length();
        double ratio = originalLength > 0
                ? (1.0 - (double) cleanedLength / originalLength) * 100 : 0;
        log.debug("文本快照清洗完成: 原始长度={}, 清洗后长度={}, 压缩比={}%",
                originalLength, cleanedLength, String.format("%.1f", ratio));

        if (cleanedLength > maxLength) {
            result = result.substring(0, maxLength) + "\n[已截断: 原始长度 " + cleanedLength + " 字符]";
            log.info("文本快照截断: 清洗后长度={}, maxLength={}", cleanedLength, maxLength);
        }

        return result;
    }
}
