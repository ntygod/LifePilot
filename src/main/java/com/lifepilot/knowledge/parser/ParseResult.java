package com.lifepilot.knowledge.parser;

import java.util.List;

/**
 * 文档解析结果 — 包含提取的文本、结构元素、元数据和警告信息。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record ParseResult(
        String text,
        List<DocumentElement> elements,
        DocumentMetadata metadata,
        List<String> warnings
) {

    /**
     * 判断解析结果是否为空（文本为 null 或空白）。
     */
    public boolean isEmpty() {
        return text == null || text.isBlank();
    }

    /**
     * 估算 Token 数量：中文按字符数，英文按空格分词。
     */
    public int estimateTokenCount() {
        if (isEmpty()) return 0;
        long chineseChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long englishWords = text.split("\\s+").length - chineseChars;
        return (int) (chineseChars + Math.max(0, englishWords));
    }
}
