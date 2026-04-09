package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.util.TextUtils;
import com.lifepilot.knowledge.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 分块合并器（Layer 3）— 合并过小分块、应用重叠、重新编号索引。
 *
 * <p>处理流程：
 * <ol>
 *   <li>合并小分块：HEADING 向后合并，同类型邻居合并，不同结构类型不合并</li>
 *   <li>应用重叠：从前一个分块尾部取 overlapSize 字符，在句子边界处截断</li>
 *   <li>重新编号：顺序设置 chunkIndex = 0, 1, 2, ...</li>
 *   <li>重新计算：更新合并/重叠后的 tokenCount 和 contentHash</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-09
 */
public class ChunkMerger {

    private static final Logger log = LoggerFactory.getLogger(ChunkMerger.class);

    /** 句子边界标点（用于重叠截断定位） */
    private static final String SENTENCE_BOUNDARIES = "。！？.!?\n";

    private final int maxChunkSize;
    private final int minChunkSize;
    private final int overlapSize;
    private final TokenCounter tokenCounter;

    /**
     * 构造分块合并器。
     *
     * @param maxChunkSize 单个分块最大字符数
     * @param minChunkSize 低于此字符数的分块会尝试合并
     * @param overlapSize  相邻分块重叠字符数
     * @param tokenCounter Token 计数器
     */
    public ChunkMerger(int maxChunkSize, int minChunkSize, int overlapSize, TokenCounter tokenCounter) {
        this.maxChunkSize = maxChunkSize;
        this.minChunkSize = minChunkSize;
        this.overlapSize = overlapSize;
        this.tokenCounter = tokenCounter;
        log.debug("初始化 ChunkMerger: maxChunkSize={}, minChunkSize={}, overlapSize={}",
                maxChunkSize, minChunkSize, overlapSize);
    }

    /**
     * 执行合并、重叠、重编号。
     *
     * @param rawChunks    原始分块列表
     * @param originalText 原始文档全文
     * @param metadata     文档元数据
     * @return 合并后的分块列表
     */
    public List<DocumentChunk> merge(List<DocumentChunk> rawChunks, String originalText,
                                      Map<String, String> metadata) {
        if (rawChunks == null || rawChunks.isEmpty()) {
            return List.of();
        }

        int beforeCount = rawChunks.size();

        // 1. 合并小分块
        var merged = mergeSmallChunks(rawChunks);

        // 2. 应用重叠
        var overlapped = applyOverlap(merged);

        // 3. 重新编号并重新计算
        var result = reindexAndRecompute(overlapped);

        log.debug("分块合并完成: 原始={}, 合并后={}, 最终={}", beforeCount, merged.size(), result.size());
        return List.copyOf(result);
    }

    // ---- 合并小分块 ----

    /**
     * 合并过小分块。
     */
    private List<DocumentChunk> mergeSmallChunks(List<DocumentChunk> inputChunks) {
        if (inputChunks.size() <= 1) return new ArrayList<>(inputChunks);

        // 转为可变列表 — 合并过程中需要修改后续元素
        var chunks = new ArrayList<>(inputChunks);
        var result = new ArrayList<DocumentChunk>();

        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);

            // 分块不小于最小尺寸，直接保留
            if (chunk.content().length() >= minChunkSize) {
                result.add(chunk);
                continue;
            }

            String chunkType = getStructureType(chunk);

            // HEADING 分块始终向后合并
            if ("HEADING".equals(chunkType) && i + 1 < chunks.size()) {
                var next = chunks.get(i + 1);
                var mergedChunk = mergeTwo(chunk, next);
                // 跳过被合并的下一个分块
                chunks.set(i + 1, mergedChunk);
                continue;
            }

            // 尝试与前一个同类型邻居合并
            if (!result.isEmpty()) {
                var prev = result.getLast();
                String prevType = getStructureType(prev);

                // 不同 section（headingHierarchy 不同）不合并 — 保持 section 边界完整
                if (!isSameSection(prev, chunk)) {
                    result.add(chunk);
                    continue;
                }

                // 同类型且合并后不超限
                if (chunkType.equals(prevType)
                        && prev.content().length() + chunk.content().length() + 1 <= maxChunkSize) {
                    result.set(result.size() - 1, mergeTwo(prev, chunk));
                    continue;
                }

                // 不同结构类型（CODE/TABLE vs PARAGRAPH）不合并
                if (isStructurallyIncompatible(prevType, chunkType)) {
                    result.add(chunk);
                    continue;
                }

                // 同类型但合并后会超限，尝试向后合并
                if (i + 1 < chunks.size()) {
                    var next = chunks.get(i + 1);
                    String nextType = getStructureType(next);
                    if (isSameSection(chunk, next) && chunkType.equals(nextType)
                            && chunk.content().length() + next.content().length() + 1 <= maxChunkSize) {
                        chunks.set(i + 1, mergeTwo(chunk, next));
                        continue;
                    }
                }
            }

            // 无法合并，保留原样
            result.add(chunk);
        }

        return result;
    }

    /**
     * 合并两个分块。
     */
    private DocumentChunk mergeTwo(DocumentChunk a, DocumentChunk b) {
        String mergedContent = a.content() + "\n" + b.content();
        int startOffset = Math.min(a.startOffset(), b.startOffset());
        int endOffset = Math.max(a.endOffset(), b.endOffset());

        // 标题层级取上下文更丰富的那个
        List<String> hierarchy = a.headingHierarchy().size() >= b.headingHierarchy().size()
                ? a.headingHierarchy() : b.headingHierarchy();

        // 元数据合并（后者覆盖前者）
        var mergedMetadata = new HashMap<>(a.metadata());
        mergedMetadata.putAll(b.metadata());

        return new DocumentChunk(
                a.id(),
                a.documentId(),
                a.knowledgeBaseId(),
                mergedContent,
                a.contextPrefix(),
                a.chunkIndex(),
                startOffset,
                endOffset,
                tokenCounter.countTokens(mergedContent),
                TextUtils.sha256(mergedContent),
                hierarchy,
                a.pageNumber(),
                Map.copyOf(mergedMetadata)
        );
    }

    /**
     * 判断两种结构类型是否不兼容（不应合并）。
     */
    private boolean isStructurallyIncompatible(String type1, String type2) {
        // CODE/TABLE 不与 PARAGRAPH 合并
        var structuralTypes = Set.of("CODE", "TABLE");
        boolean oneIsStructural = structuralTypes.contains(type1) || structuralTypes.contains(type2);
        return oneIsStructural && !type1.equals(type2);
    }

    // ---- 应用重叠 ----

    /**
     * 为相邻分块应用重叠文本。
     */
    private List<DocumentChunk> applyOverlap(List<DocumentChunk> chunks) {
        if (chunks.size() <= 1 || overlapSize <= 0) {
            return chunks;
        }

        var result = new ArrayList<DocumentChunk>();
        result.add(chunks.getFirst());

        for (int i = 1; i < chunks.size(); i++) {
            var current = chunks.get(i);
            var prev = chunks.get(i - 1);

            // 不同 section 或前一个是 HEADING 类型时不应用重叠（避免跨 section 内容渗透）
            if ("HEADING".equals(getStructureType(prev)) || !isSameSection(prev, current)) {
                result.add(current);
                continue;
            }

            String prevContent = prev.content();
            if (prevContent.length() <= overlapSize) {
                result.add(current);
                continue;
            }

            // 从前一个分块尾部取 overlapSize 字符
            int overlapStart = prevContent.length() - overlapSize;

            // 向前搜索最近的句子边界
            for (int j = overlapStart; j < prevContent.length(); j++) {
                if (SENTENCE_BOUNDARIES.indexOf(prevContent.charAt(j)) >= 0) {
                    overlapStart = j + 1;
                    break;
                }
            }

            // 如果找到的边界在前一个分块末尾，不应用重叠
            if (overlapStart >= prevContent.length()) {
                result.add(current);
                continue;
            }

            String overlapText = prevContent.substring(overlapStart);
            String newContent = overlapText + current.content();

            var overlapped = new DocumentChunk(
                    current.id(),
                    current.documentId(),
                    current.knowledgeBaseId(),
                    newContent,
                    current.contextPrefix(),
                    current.chunkIndex(),
                    current.startOffset(),
                    current.endOffset(),
                    tokenCounter.countTokens(newContent),
                    TextUtils.sha256(newContent),
                    current.headingHierarchy(),
                    current.pageNumber(),
                    current.metadata()
            );
            result.add(overlapped);
        }

        return result;
    }

    // ---- 重编号 ----

    /**
     * 重新设置 chunkIndex 并重新计算 tokenCount 和 contentHash。
     */
    private List<DocumentChunk> reindexAndRecompute(List<DocumentChunk> chunks) {
        var result = new ArrayList<DocumentChunk>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            result.add(new DocumentChunk(
                    chunk.id(),
                    chunk.documentId(),
                    chunk.knowledgeBaseId(),
                    chunk.content(),
                    chunk.contextPrefix(),
                    i,  // 重新编号
                    chunk.startOffset(),
                    chunk.endOffset(),
                    tokenCounter.countTokens(chunk.content()),
                    TextUtils.sha256(chunk.content()),
                    chunk.headingHierarchy(),
                    chunk.pageNumber(),
                    chunk.metadata()
            ));
        }
        return result;
    }

    /**
     * 判断两个分块是否属于同一 section（标题层级相同）。
     */
    private boolean isSameSection(DocumentChunk a, DocumentChunk b) {
        return a.headingHierarchy().equals(b.headingHierarchy());
    }

    /**
     * 从分块元数据获取结构类型。
     */
    private String getStructureType(DocumentChunk chunk) {
        return chunk.metadata().getOrDefault("structureType", "PARAGRAPH");
    }
}
