package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.util.TextUtils;
import com.lifepilot.knowledge.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 区域分块路由器（Layer 2）— 根据区域结构类型选择最佳分块策略。
 *
 * <p>路由策略：
 * <ul>
 *   <li>HEADING → 整块保留，记录标题层级</li>
 *   <li>PARAGRAPH → 小于阈值整块保留，超过则委派递归分块器</li>
 *   <li>CODE → 小于阈值整块保留，超过则委派固定大小分块器（硬切）</li>
 *   <li>LIST → 小于阈值整块保留，超过则按列表项拆分</li>
 *   <li>TABLE → 始终整块保留（不拆分表格行）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-09
 */
public class RegionChunkingRouter {

    private static final Logger log = LoggerFactory.getLogger(RegionChunkingRouter.class);

    /** 列表项正则（用于按列表项拆分） */
    private static final Pattern LIST_ITEM_SPLIT = Pattern.compile(
            "(?=(?:^|\\n)(?:\\s*)(?:[-*+]|\\d+[.、)]|[（(]\\d+[)）]|（[一二三四五六七八九十百]+）|第[一二三四五六七八九十百\\d]+[条章节款项])\\s+)");

    private final RecursiveChunker recursiveChunker;
    private final FixedSizeChunker fixedSizeChunker;
    private final TokenCounter tokenCounter;
    private final int maxIntactCodeSize;
    private final int maxIntactTableSize;
    private final int maxIntactListSize;
    @SuppressWarnings("unused")
    private final int paragraphMinForRecursive;
    private final int maxChunkSize;

    /**
     * 构造区域分块路由器。
     *
     * @param recursiveChunker          递归分块器
     * @param fixedSizeChunker          固定大小分块器
     * @param tokenCounter              Token 计数器
     * @param maxIntactCodeSize         代码块整块保留最大字符数
     * @param maxIntactTableSize        表格整块保留最大字符数
     * @param maxIntactListSize         列表整块保留最大字符数
     * @param paragraphMinForRecursive  段落使用递归分块的最小长度
     * @param maxChunkSize              单个分块最大字符数
     */
    public RegionChunkingRouter(RecursiveChunker recursiveChunker,
                                 FixedSizeChunker fixedSizeChunker,
                                 TokenCounter tokenCounter,
                                 int maxIntactCodeSize,
                                 int maxIntactTableSize,
                                 int maxIntactListSize,
                                 int paragraphMinForRecursive,
                                 int maxChunkSize) {
        this.recursiveChunker = recursiveChunker;
        this.fixedSizeChunker = fixedSizeChunker;
        this.tokenCounter = tokenCounter;
        this.maxIntactCodeSize = maxIntactCodeSize;
        this.maxIntactTableSize = maxIntactTableSize;
        this.maxIntactListSize = maxIntactListSize;
        this.paragraphMinForRecursive = paragraphMinForRecursive;
        this.maxChunkSize = maxChunkSize;
        log.debug("初始化 RegionChunkingRouter: maxIntactCodeSize={}, maxIntactTableSize={}, " +
                        "maxIntactListSize={}, paragraphMinForRecursive={}, maxChunkSize={}",
                maxIntactCodeSize, maxIntactTableSize, maxIntactListSize,
                paragraphMinForRecursive, maxChunkSize);
    }

    /**
     * 对所有区域执行分块路由。
     *
     * @param regions      结构区域列表
     * @param originalText 原始文档全文
     * @param metadata     文档元数据
     * @return 分块列表
     */
    public List<DocumentChunk> chunkRegions(List<StructureRegion> regions, String originalText,
                                             Map<String, String> metadata) {
        var allChunks = new ArrayList<DocumentChunk>();
        int tempIndex = 0;

        for (var region : regions) {
            // 跳过空行区域
            if (region.type() == StructureType.BLANK) {
                continue;
            }

            // 为每个区域追加 structureType 元数据
            var regionMetadata = new HashMap<>(metadata);
            regionMetadata.put("structureType", region.type().name());

            var chunks = chunkSingleRegion(region, Map.copyOf(regionMetadata));

            // 调整分块的偏移量和标题层级
            for (var chunk : chunks) {
                var adjusted = new DocumentChunk(
                        chunk.id(),
                        chunk.documentId(),
                        chunk.knowledgeBaseId(),
                        chunk.content(),
                        chunk.contextPrefix(),
                        tempIndex++,
                        chunk.startOffset(),
                        chunk.endOffset(),
                        chunk.tokenCount(),
                        chunk.contentHash(),
                        region.headingHierarchy().stream()
                                .map(h -> h.contains(":") ? h.substring(h.indexOf(':') + 1) : h)
                                .toList(),
                        chunk.pageNumber(),
                        chunk.metadata()
                );
                allChunks.add(adjusted);
            }
        }

        log.debug("区域分块路由完成: 区域数={}, 总分块数={}", regions.size(), allChunks.size());
        return List.copyOf(allChunks);
    }

    /**
     * 对单个区域执行分块。
     */
    private List<DocumentChunk> chunkSingleRegion(StructureRegion region, Map<String, String> metadata) {
        return switch (region.type()) {
            case HEADING -> chunkHeading(region, metadata);
            case PARAGRAPH -> chunkParagraph(region, metadata);
            case CODE -> chunkCode(region, metadata);
            case LIST -> chunkList(region, metadata);
            case TABLE -> chunkTable(region, metadata);
            case BLANK -> List.of(); // 空行区域不产生分块
        };
    }

    /**
     * 标题分块 — 整块保留。
     */
    private List<DocumentChunk> chunkHeading(StructureRegion region, Map<String, String> metadata) {
        return List.of(createSingleChunk(region, metadata));
    }

    /**
     * 段落分块 — 小段落整块保留，大段落委派递归分块器。
     */
    private List<DocumentChunk> chunkParagraph(StructureRegion region, Map<String, String> metadata) {
        if (region.length() <= maxChunkSize) {
            return List.of(createSingleChunk(region, metadata));
        }
        // 大段落委派递归分块器
        var subChunks = recursiveChunker.chunk(region.content(), metadata);
        return adjustSubChunkOffsets(subChunks, region.startOffset());
    }

    /**
     * 代码分块 — 小代码块整块保留，大代码块委派固定大小分块器。
     */
    private List<DocumentChunk> chunkCode(StructureRegion region, Map<String, String> metadata) {
        if (region.length() <= maxIntactCodeSize) {
            return List.of(createSingleChunk(region, metadata));
        }
        // 大代码块委派固定大小分块器（硬切，不做句子边界对齐）
        var subChunks = fixedSizeChunker.chunk(region.content(), metadata);
        return adjustSubChunkOffsets(subChunks, region.startOffset());
    }

    /**
     * 列表分块 — 小列表整块保留，大列表按列表项拆分。
     */
    private List<DocumentChunk> chunkList(StructureRegion region, Map<String, String> metadata) {
        if (region.length() <= maxIntactListSize) {
            return List.of(createSingleChunk(region, metadata));
        }

        // 按列表项拆分
        String[] items = LIST_ITEM_SPLIT.split(region.content());
        var chunks = new ArrayList<DocumentChunk>();
        int localOffset = 0;

        for (String item : items) {
            if (item.isBlank()) {
                localOffset += item.length();
                continue;
            }
            String trimmedItem = item.stripTrailing();
            int itemStart = region.content().indexOf(item, localOffset);
            if (itemStart < 0) itemStart = localOffset;

            chunks.add(new DocumentChunk(
                    UUID.randomUUID().toString(),
                    "", "",
                    trimmedItem,
                    Optional.empty(),
                    0,
                    region.startOffset() + itemStart,
                    region.startOffset() + itemStart + trimmedItem.length(),
                    tokenCounter.countTokens(trimmedItem),
                    TextUtils.sha256(trimmedItem),
                    List.of(),
                    0,
                    metadata
            ));
            localOffset = itemStart + item.length();
        }

        return chunks;
    }

    /**
     * 表格分块 — 始终整块保留（不拆分表格行）。
     */
    private List<DocumentChunk> chunkTable(StructureRegion region, Map<String, String> metadata) {
        if (region.length() > maxIntactTableSize) {
            log.warn("表格区域过大但仍整块保留（不拆分表格行）: startOffset={}, 字符数={}",
                    region.startOffset(), region.length());
        }
        return List.of(createSingleChunk(region, metadata));
    }

    /**
     * 创建整块保留的单个分块。
     */
    private DocumentChunk createSingleChunk(StructureRegion region, Map<String, String> metadata) {
        return new DocumentChunk(
                UUID.randomUUID().toString(),
                "", "",
                region.content(),
                Optional.empty(),
                0,
                region.startOffset(),
                region.endOffset(),
                tokenCounter.countTokens(region.content()),
                TextUtils.sha256(region.content()),
                List.of(),
                0,
                metadata
        );
    }

    /**
     * 调整子分块的偏移量（相对于区域起始偏移量）。
     */
    private List<DocumentChunk> adjustSubChunkOffsets(List<DocumentChunk> subChunks, int regionStartOffset) {
        return subChunks.stream()
                .map(c -> new DocumentChunk(
                        c.id(),
                        c.documentId(),
                        c.knowledgeBaseId(),
                        c.content(),
                        c.contextPrefix(),
                        c.chunkIndex(),
                        regionStartOffset + c.startOffset(),
                        regionStartOffset + c.endOffset(),
                        c.tokenCount(),
                        c.contentHash(),
                        c.headingHierarchy(),
                        c.pageNumber(),
                        c.metadata()
                ))
                .toList();
    }
}
