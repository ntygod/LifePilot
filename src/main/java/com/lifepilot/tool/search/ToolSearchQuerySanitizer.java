package com.lifepilot.tool.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * FTS5 MATCH 查询消毒器（trigram tokenizer 适配版）。
 *
 * <p>trigram tokenizer 下 phrase search 行为：phrase 长度 ≥ 3 时，
 * FTS5 在索引文本中做 substring 匹配（不要求完整 token 命中）。</p>
 *
 * <p>策略：
 * <ul>
 *   <li>把每个 token 切成 3-gram phrase 用 {@code OR} 连接，让短 query 也能命中</li>
 *   <li>token 长度 == 2 用空格前缀凑 3 字符（命中索引中"空格+词"边界）</li>
 *   <li>FTS5 保留字符 {@code "()*} 替换为空格</li>
 *   <li>FTS5 保留关键字 {@code AND/OR/NOT/NEAR} 转小写规避被识别为操作符</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-23
 */
public class ToolSearchQuerySanitizer {

    private static final int TRIGRAM_LENGTH = 3;

    /** FTS5 保留关键字（ASCII 大写敏感）。匹配后转小写避免被识别为操作符。 */
    private static final Pattern KEYWORDS = Pattern.compile("\\b(AND|OR|NOT|NEAR)\\b");

    /**
     * 消毒查询字符串。
     *
     * @param query 原始查询（可为 null / 空）
     * @return FTS5 可安全执行的 MATCH 表达式；输入为空时返回空字符串
     */
    public String sanitize(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String cleaned = query.replaceAll("[\"()*]", " ");
        cleaned = KEYWORDS.matcher(cleaned).replaceAll(m -> m.group().toLowerCase(Locale.ROOT));

        List<String> phrases = new ArrayList<>();
        for (String token : cleaned.split("[\\s\\p{Punct}]+")) {
            if (token.length() < 2) continue;
            if (token.length() < TRIGRAM_LENGTH) {
                // 2 字符 token 用空格前缀凑 trigram，命中索引"空格+词"边界
                phrases.add("\" " + token + "\"");
                continue;
            }
            // ≥ 3 字符：按 3-gram 滑窗切多个 phrase
            for (int i = 0; i <= token.length() - TRIGRAM_LENGTH; i++) {
                phrases.add("\"" + token.substring(i, i + TRIGRAM_LENGTH) + "\"");
            }
        }
        return phrases.isEmpty() ? "" : String.join(" OR ", phrases);
    }
}
