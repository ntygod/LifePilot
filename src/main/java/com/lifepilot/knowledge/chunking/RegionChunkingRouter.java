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
     * <p>先将 HEADING + 其管辖的 PARAGRAPH/LIST 等合并为逻辑 section，
     * 再对每个 section 整体路由分块，确保标题和内容不被拆散。</p>
     *
     * @param regions      结构区域列表
     * @param originalText 原始文档全文
     * @param metadata     文档元数据
     * @return 分块列表
     */
    public List<DocumentChunk> chunkRegions(List<StructureRegion> regions, String originalText,
                                             Map<String, String> metadata) {
        // 1. 将 HEADING + 后续内容区域合并为 section
        var sections = groupIntoSections(regions, originalText);

        var allChunks = new ArrayList<DocumentChunk>();
        int tempIndex = 0;

        for (var section : sections) {
            // 为每个 section 追加 structureType 元数据
            var sectionMetadata = new HashMap<>(metadata);
            sectionMetadata.put("structureType", section.type().name());

            var chunks = chunkSingleRegion(section, Map.copyOf(sectionMetadata));

            // 设置标题层级和全局索引
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
                        section.headingHierarchy().stream()
                                .map(h -> h.contains(":") ? h.substring(h.indexOf(':') + 1) : h)
                                .toList(),
                        chunk.pageNumber(),
                        chunk.metadata()
                );
                allChunks.add(adjusted);
            }
        }

        log.debug("区域分块路由完成: 区域数={}, section数={}, 总分块数={}",
                regions.size(), sections.size(), allChunks.size());
        return List.copyOf(allChunks);
    }

    /**
     * 将 HEADING 与其管辖的后续内容区域合并为逻辑 section。
     *
     * <p>先按每个 HEADING 切分为原始 section，然后合并过小的 section：
     * 如果某个 section 的内容（不含标题）小于 minSectionSize，
     * 将其与前一个 section 合并（视为前一 section 的子标题内容）。</p>
     */
    private List<StructureRegion> groupIntoSections(List<StructureRegion> regions, String originalText) {
        // 1. 按 HEADING 分割为原始 section
        var rawSections = splitByHeading(regions, originalText);

        // 2. 合并过小的 section（子标题归入上级 section）
        return mergeSmallSections(rawSections, originalText);
    }

    /** 按每个 HEADING 分割为原始 section。 */
    private List<StructureRegion> splitByHeading(List<StructureRegion> regions, String originalText) {
        var sections = new ArrayList<StructureRegion>();
        int i = 0;

        while (i < regions.size()) {
            var region = regions.get(i);

            if (region.type() == StructureType.HEADING) {
                int sectionStart = region.startOffset();
                var headingHierarchy = region.headingHierarchy();
                int j = i + 1;
                while (j < regions.size() && regions.get(j).type() != StructureType.HEADING) {
                    j++;
                }
                int sectionEnd = regions.get(j - 1).endOffset();
                String sectionContent = originalText.substring(sectionStart,
                        Math.min(sectionEnd, originalText.length()));

                var allLines = new ArrayList<AnnotatedLine>();
                for (int k = i; k < j; k++) {
                    allLines.addAll(regions.get(k).lines());
                }

                sections.add(new StructureRegion(
                        StructureType.PARAGRAPH,
                        sectionStart, sectionEnd, sectionContent,
                        allLines, headingHierarchy
                ));
                i = j;
            } else if (region.type() == StructureType.BLANK) {
                i++;
            } else {
                sections.add(region);
                i++;
            }
        }

        return sections;
    }

    /**
     * 合并过小的 section — 尊重标题边界：有标题的 section 只能向前合并（保留标题身份），无标题的优先向后合并。
     *
     * <p>合并规则：
     * <ul>
     *   <li>无独立标题的微小 section → 优先向后合并，首 section 向前合并</li>
     *   <li>有独立标题的微小 section → 仅向前合并（吸收下一个 section 内容，保留自身标题）</li>
     * </ul>
     */
    private List<StructureRegion> mergeSmallSections(List<StructureRegion> sections, String originalText) {
        if (sections.size() <= 1) return sections;

        var merged = new ArrayList<StructureRegion>();
        int minSectionSize = paragraphMinForRecursive;

        for (int i = 0; i < sections.size(); i++) {
            var section = sections.get(i);

            if (section.length() >= minSectionSize) {
                merged.add(section);
                continue;
            }

            // 判断是否有独立标题
            boolean hasOwnHeading = !section.headingHierarchy().isEmpty()
                    && (merged.isEmpty() || !section.headingHierarchy().equals(merged.getLast().headingHierarchy()));

            if (hasOwnHeading) {
                // 有独立标题 → 只向前合并（吸收下一个 section，保留自身标题）
                if (i + 1 < sections.size()) {
                    var next = sections.get(i + 1);
                    if (section.length() + next.length() <= maxChunkSize) {
                        sections.set(i + 1, combineSections(section, next, originalText,
                                section.headingHierarchy()));
                        continue;
                    }
                }
                // 无法向前合并（已是最后 section 或合并后超限），保留原样
                merged.add(section);
            } else {
                // 无独立标题 → 优先向后合并
                if (!merged.isEmpty()) {
                    var prev = merged.getLast();
                    if (prev.length() + section.length() <= maxChunkSize) {
                        merged.set(merged.size() - 1,
                                combineSections(prev, section, originalText, prev.headingHierarchy()));
                        continue;
                    }
                }
                // 向前合并
                if (i + 1 < sections.size()) {
                    var next = sections.get(i + 1);
                    if (section.length() + next.length() <= maxChunkSize) {
                        sections.set(i + 1, combineSections(section, next, originalText,
                                section.headingHierarchy()));
                        continue;
                    }
                }
                merged.add(section);
            }
        }

        return merged;
    }

    /** 合并两个相邻 section 为一个。 */
    private StructureRegion combineSections(StructureRegion a, StructureRegion b,
                                             String originalText, List<String> headingHierarchy) {
        int mergedStart = a.startOffset();
        int mergedEnd = b.endOffset();
        String mergedContent = originalText.substring(mergedStart,
                Math.min(mergedEnd, originalText.length()));
        var mergedLines = new ArrayList<>(a.lines());
        mergedLines.addAll(b.lines());
        return new StructureRegion(
                StructureType.PARAGRAPH,
                mergedStart, mergedEnd, mergedContent,
                mergedLines, headingHierarchy
        );
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
     * 段落分块 — 小段落整块保留，大段落委派递归分块器（不应用重叠，由 ChunkMerger 统一处理）。
     */
    private List<DocumentChunk> chunkParagraph(StructureRegion region, Map<String, String> metadata) {
        if (region.length() <= maxChunkSize) {
            return List.of(createSingleChunk(region, metadata));
        }
        // 大段落委派递归分块器 — 不应用重叠，避免与 ChunkMerger 双重重叠
        var subChunks = recursiveChunker.chunkWithoutOverlap(region.content(), metadata);
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
