package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.util.TextUtils;
import com.lifepilot.knowledge.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题层级分块器 — 按文档标题结构切分。
 *
 * <p>每个标题节作为一个分块，超长节委托 {@link RecursiveChunker} 二次切分。
 * 无标题文档整体委托 RecursiveChunker。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class HeadingChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(HeadingChunker.class);

    private final RecursiveChunker fallbackChunker;
    private final TokenCounter tokenCounter;
    private final int maxHeadingLevel;
    private final int maxChunkSize;
    private final Pattern headingPattern;

    /**
     * 构造标题层级分块器。
     *
     * @param fallbackChunker 超长节回退用的递归分块器
     * @param headingConfig   标题分块专用配置
     * @param tokenCounter    Token 计数器
     */
    public HeadingChunker(RecursiveChunker fallbackChunker,
                          KnowledgeBaseProperties.Chunking.Heading headingConfig,
                          TokenCounter tokenCounter) {
        this.fallbackChunker = fallbackChunker;
        this.tokenCounter = tokenCounter;
        this.maxHeadingLevel = headingConfig.maxHeadingLevel();
        this.maxChunkSize = headingConfig.maxChunkSize();
        this.headingPattern = Pattern.compile(
                "^(#{1," + maxHeadingLevel + "})\\s+(.+)", Pattern.MULTILINE);
        log.debug("初始化 HeadingChunker: maxHeadingLevel={}, maxChunkSize={}",
                maxHeadingLevel, maxChunkSize);
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 查找所有标题位置
        List<HeadingInfo> headings = findHeadings(text);

        // 无标题文档委托 RecursiveChunker
        if (headings.isEmpty()) {
            log.debug("文档无标题，委托 RecursiveChunker");
            return fallbackChunker.chunk(text, metadata);
        }

        // 按标题切分为节
        List<Section> sections = splitIntoSections(text, headings);

        // 构建 DocumentChunk 列表
        List<DocumentChunk> result = new ArrayList<>();
        int chunkIndex = 0;

        for (Section section : sections) {
            if (section.content.length() > maxChunkSize) {
                // 超长节委托 RecursiveChunker 二次切分
                List<DocumentChunk> subChunks = fallbackChunker.chunk(section.content, metadata);
                for (DocumentChunk sub : subChunks) {
                    result.add(new DocumentChunk(
                            UUID.randomUUID().toString(),
                            "", "",
                            sub.content(),
                            Optional.empty(),
                            chunkIndex++,
                            section.startOffset + sub.startOffset(),
                            section.startOffset + sub.endOffset(),
                            sub.tokenCount(),
                            sub.contentHash(),
                            List.copyOf(section.headingHierarchy),
                            0, metadata
                    ));
                }
            } else {
                result.add(new DocumentChunk(
                        UUID.randomUUID().toString(),
                        "", "",
                        section.content,
                        Optional.empty(),
                        chunkIndex++,
                        section.startOffset,
                        section.endOffset,
                        tokenCounter.countTokens(section.content),
                        TextUtils.sha256(section.content),
                        List.copyOf(section.headingHierarchy),
                        0, metadata
                ));
            }
        }

        log.debug("标题分块完成: 标题数={}, 节数={}, 最终分块={}",
                headings.size(), sections.size(), result.size());
        return List.copyOf(result);
    }

    @Override
    public int estimateChunkCount(int textLength) {
        if (textLength <= 0) return 0;
        // 粗略估算：假设每 500 字符一个标题节
        return Math.max(1, textLength / 500);
    }

    @Override
    public String strategyName() {
        return "heading";
    }

    /**
     * 查找文本中所有标题。
     */
    private List<HeadingInfo> findHeadings(String text) {
        List<HeadingInfo> headings = new ArrayList<>();
        Matcher matcher = headingPattern.matcher(text);
        while (matcher.find()) {
            int level = matcher.group(1).length();
            String title = matcher.group(2).trim();
            headings.add(new HeadingInfo(level, title, matcher.start(), matcher.end()));
        }
        return headings;
    }

    /**
     * 按标题位置将文本切分为节。
     */
    private List<Section> splitIntoSections(String text, List<HeadingInfo> headings) {
        List<Section> sections = new ArrayList<>();

        // 标题前的内容作为前言节
        if (headings.getFirst().startOffset > 0) {
            String preamble = text.substring(0, headings.getFirst().startOffset).trim();
            if (!preamble.isEmpty()) {
                sections.add(new Section(preamble, 0, headings.getFirst().startOffset, List.of()));
            }
        }

        // 维护标题层级栈
        Deque<HeadingInfo> hierarchyStack = new ArrayDeque<>();

        for (int i = 0; i < headings.size(); i++) {
            HeadingInfo heading = headings.get(i);
            int contentStart = heading.startOffset;
            int contentEnd = (i + 1 < headings.size())
                    ? headings.get(i + 1).startOffset
                    : text.length();

            // 更新层级栈：弹出同级或更低级的标题
            while (!hierarchyStack.isEmpty() && hierarchyStack.peek().level >= heading.level) {
                hierarchyStack.pop();
            }
            hierarchyStack.push(heading);

            // 构建面包屑路径（从栈底到栈顶）
            List<String> breadcrumb = new ArrayList<>();
            for (HeadingInfo h : hierarchyStack.reversed()) {
                breadcrumb.add(h.title);
            }

            String content = text.substring(contentStart, contentEnd).trim();
            if (!content.isEmpty()) {
                sections.add(new Section(content, contentStart, contentEnd, breadcrumb));
            }
        }

        return sections;
    }

    /** 标题信息。 */
    private record HeadingInfo(int level, String title, int startOffset, int endOffset) {}

    /** 文档节。 */
    private record Section(String content, int startOffset, int endOffset,
                           List<String> headingHierarchy) {}

}
