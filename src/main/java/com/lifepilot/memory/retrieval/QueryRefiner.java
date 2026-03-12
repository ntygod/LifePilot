package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.config.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * 查询精炼器 — 对用户原始输入进行清洗和截断，提升检索召回质量。
 *
 * @author zsg
 * @since 2026-03-12
 */
public class QueryRefiner {

    private static final Logger log = LoggerFactory.getLogger(QueryRefiner.class);

    /** 中文口语填充词正则：嗯、啊、呃、那个、就是说、然后呢、对吧、你知道吗 等。 */
    private static final Pattern FILLER_PATTERN = Pattern.compile(
            "(嗯+|啊+|呃+|额+|哦+|那个|就是说|然后呢|对吧|你知道吗|怎么说呢|反正就是|我觉得吧)");

    private final MemoryProperties.Retrieval retrievalConfig;

    public QueryRefiner(MemoryProperties memoryProperties) {
        this.retrievalConfig = memoryProperties.getRetrieval();
    }

    /**
     * 精炼查询文本。
     *
     * @param rawInput 用户原始输入
     * @return 精炼后的查询文本
     */
    public String refine(String rawInput) {
        // null / 空白 → 空字符串
        if (rawInput == null || rawInput.isBlank()) {
            return "";
        }

        String trimmed = rawInput.trim();

        // 短于 queryMinLength → 直接返回原文
        if (trimmed.length() < retrievalConfig.getQueryMinLength()) {
            return trimmed;
        }

        // 移除中文口语填充词
        String refined = FILLER_PATTERN.matcher(trimmed).replaceAll("").trim();

        // 精炼后为空 → 兜底返回原文
        if (refined.isEmpty()) {
            log.debug("查询精炼: 精炼后为空，兜底返回原文, rawInput={}", trimmed);
            return trimmed;
        }

        // 超过 queryMaxLength → 截断
        int maxLen = retrievalConfig.getQueryMaxLength();
        if (refined.length() > maxLen) {
            refined = refined.substring(0, maxLen);
            log.debug("查询精炼: 截断至 {} 字符", maxLen);
        }

        return refined;
    }
}
