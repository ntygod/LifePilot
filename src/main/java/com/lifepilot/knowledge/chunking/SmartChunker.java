package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.parser.DocumentElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 三层结构分块器 — 始终通过三层架构处理文档，按区域类型自动路由分块策略。
 *
 * <p>三层管线：
 * <ol>
 *   <li>{@link DocumentStructureAnalyzer} — 逐行分类并合并为结构区域</li>
 *   <li>{@link RegionChunkingRouter} — 按区域类型（标题/段落/代码/列表/表格）路由至对应分块器</li>
 *   <li>{@link ChunkMerger} — 合并过小分块、应用重叠、重新编号</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class SmartChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(SmartChunker.class);

    private final RecursiveChunker recursiveChunker;
    private final DocumentStructureAnalyzer structureAnalyzer;
    private final RegionChunkingRouter regionRouter;
    private final ChunkMerger chunkMerger;

    /**
     * 构造三层结构分块器。
     *
     * @param recursiveChunker  递归分块器（用于 chunk 数量估算）
     * @param structureAnalyzer 文档结构分析器（Layer 1）
     * @param regionRouter      区域分块路由器（Layer 2）
     * @param chunkMerger       分块合并器（Layer 3）
     */
    public SmartChunker(RecursiveChunker recursiveChunker,
                        DocumentStructureAnalyzer structureAnalyzer,
                        RegionChunkingRouter regionRouter,
                        ChunkMerger chunkMerger) {
        this.recursiveChunker = recursiveChunker;
        this.structureAnalyzer = structureAnalyzer;
        this.regionRouter = regionRouter;
        this.chunkMerger = chunkMerger;
        log.info("初始化 SmartChunker: 三层分块架构（结构分析 → 区域路由 → 合并后处理）");
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

        log.debug("使用三层分块架构处理文档: 文本长度={}, 解析器元素={}", text.length(),
                parserElements != null ? parserElements.size() : 0);
        var regions = structureAnalyzer.analyze(text, parserElements);
        var rawChunks = regionRouter.chunkRegions(regions, text, metadata);
        return chunkMerger.merge(rawChunks, text, metadata);
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        return chunk(text, metadata, null);
    }

    @Override
    public int estimateChunkCount(int textLength) {
        return recursiveChunker.estimateChunkCount(textLength);
    }

    @Override
    public String strategyName() {
        return "smart";
    }
}
