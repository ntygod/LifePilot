package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.parser.DocumentElement;
import org.springframework.lang.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能策略选择器 — 分析文档特征自动选择最佳分块策略。
 *
 * <p>选择逻辑（优先级从高到低）：
 * <ol>
 *   <li>代码块密度 &gt; codeBlockDensityThreshold → RecursiveChunker（保留代码块完整性）</li>
 *   <li>标题密度 &gt; headingDensityThreshold → HeadingChunker</li>
 *   <li>semanticChunker 可用且文档长度 &gt; semanticChunkingThreshold → SemanticChunker</li>
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

    /** Markdown 围栏代码块正则 */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```[\\s\\S]*?```", Pattern.MULTILINE);

    private final FixedSizeChunker fixedSizeChunker;
    private final RecursiveChunker recursiveChunker;
    private final HeadingChunker headingChunker;
    @Nullable
    private final SemanticChunker semanticChunker;
    private final double headingDensityThreshold;
    private final int shortDocumentThreshold;
    private final double codeBlockDensityThreshold;
    private final int semanticChunkingThreshold;

    // 三层分块架构组件
    private final DocumentStructureAnalyzer structureAnalyzer;
    private final RegionChunkingRouter regionRouter;
    private final ChunkMerger chunkMerger;

    /**
     * 构造智能策略选择器（兼容旧版，不含三层分块组件）。
     *
     * @param fixedSizeChunker  固定大小分块器
     * @param recursiveChunker  递归分块器
     * @param headingChunker    标题分块器
     * @param semanticChunker   语义分块器（可选，为 null 时跳过语义分块策略）
     * @param smartConfig       智能选择配置
     */
    public SmartChunker(FixedSizeChunker fixedSizeChunker,
                        RecursiveChunker recursiveChunker,
                        HeadingChunker headingChunker,
                        @Nullable SemanticChunker semanticChunker,
                        KnowledgeBaseProperties.Chunking.SmartChunker smartConfig) {
        this(fixedSizeChunker, recursiveChunker, headingChunker, semanticChunker, smartConfig,
                null, null, null);
    }

    /**
     * 构造智能策略选择器（含三层分块组件）。
     *
     * @param fixedSizeChunker   固定大小分块器
     * @param recursiveChunker   递归分块器
     * @param headingChunker     标题分块器
     * @param semanticChunker    语义分块器（可选）
     * @param smartConfig        智能选择配置
     * @param structureAnalyzer  文档结构分析器（可选）
     * @param regionRouter       区域分块路由器（可选）
     * @param chunkMerger        分块合并器（可选）
     */
    public SmartChunker(FixedSizeChunker fixedSizeChunker,
                        RecursiveChunker recursiveChunker,
                        HeadingChunker headingChunker,
                        @Nullable SemanticChunker semanticChunker,
                        KnowledgeBaseProperties.Chunking.SmartChunker smartConfig,
                        @Nullable DocumentStructureAnalyzer structureAnalyzer,
                        @Nullable RegionChunkingRouter regionRouter,
                        @Nullable ChunkMerger chunkMerger) {
        this.fixedSizeChunker = fixedSizeChunker;
        this.recursiveChunker = recursiveChunker;
        this.headingChunker = headingChunker;
        this.semanticChunker = semanticChunker;
        this.headingDensityThreshold = smartConfig.headingDensityThreshold();
        this.shortDocumentThreshold = smartConfig.shortDocumentThreshold();
        this.codeBlockDensityThreshold = smartConfig.codeBlockDensityThreshold();
        this.semanticChunkingThreshold = smartConfig.semanticChunkingThreshold();
        this.structureAnalyzer = structureAnalyzer;
        this.regionRouter = regionRouter;
        this.chunkMerger = chunkMerger;
        log.debug("初始化 SmartChunker: headingDensityThreshold={}, shortDocumentThreshold={}, " +
                        "codeBlockDensityThreshold={}, semanticChunkingThreshold={}, semanticChunker={}, " +
                        "三层分块={}",
                headingDensityThreshold, shortDocumentThreshold,
                codeBlockDensityThreshold, semanticChunkingThreshold,
                semanticChunker != null ? "可用" : "不可用",
                structureAnalyzer != null ? "启用" : "未启用");
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

        // 计算文档特征
        double codeBlockDensity = calcCodeBlockDensity(text);
        int headingCount = countHeadings(text);
        int paragraphCount = countParagraphs(text);
        double headingDensity = paragraphCount > 0
                ? (double) headingCount / paragraphCount
                : 0.0;

        // 1. 代码块密度高 → RecursiveChunker（保留代码块完整性）
        if (codeBlockDensity > codeBlockDensityThreshold) {
            log.debug("选择 RecursiveChunker（代码块密度高）: codeBlockDensity={}, threshold={}",
                    codeBlockDensity, codeBlockDensityThreshold);
            return recursiveChunker;
        }

        // 2. 标题密度高 → HeadingChunker
        if (headingDensity > headingDensityThreshold) {
            log.debug("选择 HeadingChunker: headingDensity={}, threshold={}",
                    headingDensity, headingDensityThreshold);
            return headingChunker;
        }

        // 3. 语义分块器可用且文档足够长 → SemanticChunker
        if (semanticChunker != null && text.length() > semanticChunkingThreshold) {
            log.debug("选择 SemanticChunker: textLength={}, threshold={}",
                    text.length(), semanticChunkingThreshold);
            return semanticChunker;
        }

        // 4. 有段落结构或长文档 → RecursiveChunker（尊重 \n\n 段落边界）
        if (text.length() > shortDocumentThreshold || text.contains("\n\n")) {
            log.debug("选择 RecursiveChunker: textLength={}, hasParagraphs={}, threshold={}",
                    text.length(), text.contains("\n\n"), shortDocumentThreshold);
            return recursiveChunker;
        }

        // 5. 无段落结构的短文本 → FixedSizeChunker
        log.debug("选择 FixedSizeChunker: textLength={}", text.length());
        return fixedSizeChunker;
    }

    /**
     * 使用三层分块架构处理文档（携带解析器元素辅助分析）。
     *
     * @param text           文档全文
     * @param metadata       文档元数据
     * @param parserElements 解析器提取的文档元素（可选）
     * @return 分块列表
     */
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata,
                                      @Nullable List<DocumentElement> parserElements) {
        if (text == null || text.isBlank()) return List.of();

        // 三层分块组件可用时走新路径
        if (structureAnalyzer != null && regionRouter != null && chunkMerger != null) {
            log.debug("使用三层分块架构处理文档: 文本长度={}, 解析器元素={}", text.length(),
                    parserElements != null ? parserElements.size() : 0);
            var regions = structureAnalyzer.analyze(text, parserElements);
            var rawChunks = regionRouter.chunkRegions(regions, text, metadata);
            return chunkMerger.merge(rawChunks, text, metadata);
        }

        // 回退到旧策略选择模式
        return chunk(text, metadata);
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        // 三层分块组件可用时委派到新方法
        if (structureAnalyzer != null && regionRouter != null && chunkMerger != null) {
            return chunk(text, metadata, null);
        }
        // 旧策略选择模式
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
     * 计算代码块字符数占总字符数的比例。
     */
    private double calcCodeBlockDensity(String text) {
        if (text.isEmpty()) return 0.0;
        int codeChars = 0;
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
        while (matcher.find()) {
            codeChars += matcher.end() - matcher.start();
        }
        return (double) codeChars / text.length();
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
