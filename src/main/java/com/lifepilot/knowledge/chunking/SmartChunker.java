package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能策略选择器 — 分析文档特征自动选择最佳分块策略。
 *
 * <p>选择逻辑：
 * <ol>
 *   <li>标题密度 &gt; headingDensityThreshold → HeadingChunker</li>
 *   <li>文本长度 &gt; shortDocumentThreshold → RecursiveChunker</li>
 *   <li>否则 → FixedSizeChunker</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class SmartChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(SmartChunker.class);

    /** ATX 标题正则（1-6 级） */
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^#{1,6}\\s+.+", Pattern.MULTILINE);

    private final FixedSizeChunker fixedSizeChunker;
    private final RecursiveChunker recursiveChunker;
    private final HeadingChunker headingChunker;
    private final double headingDensityThreshold;
    private final int shortDocumentThreshold;

    /**
     * 构造智能策略选择器。
     *
     * @param fixedSizeChunker 固定大小分块器
     * @param recursiveChunker 递归分块器
     * @param headingChunker   标题分块器
     * @param smartConfig      智能选择配置
     */
    public SmartChunker(FixedSizeChunker fixedSizeChunker,
                        RecursiveChunker recursiveChunker,
                        HeadingChunker headingChunker,
                        KnowledgeBaseProperties.Chunking.SmartChunker smartConfig) {
        this.fixedSizeChunker = fixedSizeChunker;
        this.recursiveChunker = recursiveChunker;
        this.headingChunker = headingChunker;
        this.headingDensityThreshold = smartConfig.headingDensityThreshold();
        this.shortDocumentThreshold = smartConfig.shortDocumentThreshold();
        log.debug("初始化 SmartChunker: headingDensityThreshold={}, shortDocumentThreshold={}",
                headingDensityThreshold, shortDocumentThreshold);
    }

    /**
     * 根据文档特征选择最佳分块策略。
     *
     * @param text     文档文本
     * @param metadata 文档元数据
     * @return 选中的分块策略
     */
    public ChunkingStrategy selectStrategy(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            return fixedSizeChunker;
        }

        // 计算标题密度
        int headingCount = countHeadings(text);
        int paragraphCount = countParagraphs(text);
        double headingDensity = paragraphCount > 0
                ? (double) headingCount / paragraphCount
                : 0.0;

        if (headingDensity > headingDensityThreshold) {
            log.debug("选择 HeadingChunker: headingDensity={}, threshold={}",
                    headingDensity, headingDensityThreshold);
            return headingChunker;
        }

        if (text.length() > shortDocumentThreshold) {
            log.debug("选择 RecursiveChunker: textLength={}, threshold={}",
                    text.length(), shortDocumentThreshold);
            return recursiveChunker;
        }

        log.debug("选择 FixedSizeChunker: textLength={}", text.length());
        return fixedSizeChunker;
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        ChunkingStrategy selected = selectStrategy(text, metadata);
        return selected.chunk(text, metadata);
    }

    @Override
    public int estimateChunkCount(int textLength) {
        // 使用递归分块器的估算作为默认
        return recursiveChunker.estimateChunkCount(textLength);
    }

    @Override
    public String strategyName() {
        return "smart";
    }

    /**
     * 统计文本中的标题数量。
     */
    private int countHeadings(String text) {
        int count = 0;
        Matcher matcher = HEADING_PATTERN.matcher(text);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 统计文本中的段落数量（以空行分隔）。
     */
    private int countParagraphs(String text) {
        String[] paragraphs = text.split("\n\n+");
        int count = 0;
        for (String p : paragraphs) {
            if (!p.isBlank()) {
                count++;
            }
        }
        return Math.max(1, count);
    }
}
