package com.lifepilot.meta.infra.browser;

import java.util.regex.Pattern;

/**
 * 浏览器文本快照清洗器 — 移除 JS/CSS/全局状态等无语义噪声，保留页面核心内容。
 *
 * <p>清洗规则按顺序执行：
 * <ol>
 *   <li>移除 {@code <script>} 标签及其内容</li>
 *   <li>移除 {@code <style>} 标签及其内容</li>
 *   <li>移除 {@code window.__pinia} 等全局状态注入行</li>
 *   <li>移除 CSS 资源 URL 行（如 {@code url(data:...)} 或 {@code https://...*.css}）</li>
 *   <li>移除嵌套 JSON 配置块（以 {@code {" } 开头的长行）</li>
 *   <li>压缩连续空行为单个空行</li>
 *   <li>截断超长文本</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-15
 */
public class TextSnapshotCleaner {

    /** 匹配 script 标签及内容（含多行）。 */
    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "<script[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);

    /** 匹配 style 标签及内容（含多行）。 */
    private static final Pattern STYLE_TAG = Pattern.compile(
            "<style[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);

    /** 匹配全局状态注入行（window.__pinia、window.__INITIAL_STATE__ 等）。 */
    private static final Pattern GLOBAL_STATE = Pattern.compile(
            "(?m)^.*window\\.__[a-zA-Z_]+.*$");

    /** 匹配 CSS 资源 URL 行。 */
    private static final Pattern CSS_URL_LINE = Pattern.compile(
            "(?m)^.*(?:url\\(data:|https?://[^\\s]*\\.css).*$");

    /** 匹配以 {" 开头且长度超过 200 字符的 JSON 配置行。 */
    private static final Pattern JSON_CONFIG_LINE = Pattern.compile(
            "(?m)^\\s*\\{\"[^\\n]{200,}$");

    /** 压缩连续空行（3 个及以上换行）为双换行。 */
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

        String result = rawSnapshot;
        result = SCRIPT_TAG.matcher(result).replaceAll("");
        result = STYLE_TAG.matcher(result).replaceAll("");
        result = GLOBAL_STATE.matcher(result).replaceAll("");
        result = CSS_URL_LINE.matcher(result).replaceAll("");
        result = JSON_CONFIG_LINE.matcher(result).replaceAll("");
        result = CONSECUTIVE_BLANK_LINES.matcher(result).replaceAll("\n\n");
        result = result.strip();

        if (result.length() > maxLength) {
            int originalLength = result.length();
            result = result.substring(0, maxLength) + "\n[已截断: 原始长度 " + originalLength + " 字符]";
        }

        return result;
    }
}
