package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.util.TextUtils;
import com.lifepilot.knowledge.util.TokenCounter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 固定大小分块器 — 按固定字符数切分文本，支持重叠和句子边界对齐。
 *
 * <p>算法流程：
 * <ol>
 *   <li>从当前位置向后取 maxChunkSize 个字符</li>
 *   <li>若 respectSentences 为 true，在句子边界处对齐（中文：。！？；，英文：.!?\n）</li>
 *   <li>应用 overlapSize 重叠</li>
 *   <li>最后一个分块小于 minChunkSize 时与前一个合并</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class FixedSizeChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(FixedSizeChunker.class);

    /**
     * 分层句子边界标点 — 优先级从高到低。
     *
     * <p>不包含 {@code ，、：:} 等句内标点，避免在逗号处断开导致语义碎片。</p>
     */
    private static final String STRONG_BOUNDARIES = "。！？.!?";
    private static final String WEAK_BOUNDARIES = "；;\n";

    private final ChunkingConfig config;
    private final TokenCounter tokenCounter;

    /**
     * 构造固定大小分块器。
     *
     * @param config       分块配置
     * @param tokenCounter Token 计数器
     */
    public FixedSizeChunker(ChunkingConfig config, TokenCounter tokenCounter) {
        this.config = config;
        this.tokenCounter = tokenCounter;
        log.debug("初始化 FixedSizeChunker: maxChunkSize={}, minChunkSize={}, overlapSize={}, respectSentences={}",
                config.maxChunkSize(), config.minChunkSize(), config.overlapSize(), config.respectSentences());
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            log.debug("输入文本为空，返回空分块列表");
            return List.of();
        }

        List<DocumentChunk> chunks = new ArrayList<>();
        int position = 0;
        int chunkIndex = 0;
        int textLength = text.length();

        while (position < textLength) {
            int end = Math.min(position + config.maxChunkSize(), textLength);

            // 句子边界对齐
            if (end < textLength && config.respectSentences()) {
                int sentenceEnd = findNearestSentenceEnd(text, position, end);
                if (sentenceEnd > position + config.minChunkSize()) {
                    end = sentenceEnd;
                }
            }

            String chunkContent = text.substring(position, end).trim();

            if (!chunkContent.isEmpty()) {
                // 最后一个分块太小则与前一个合并
                if (chunkContent.length() < config.minChunkSize() && !chunks.isEmpty() && end >= textLength) {
                    DocumentChunk lastChunk = chunks.removeLast();
                    chunkContent = lastChunk.content() + "\n" + chunkContent;
                    position = lastChunk.startOffset();
                    chunkIndex--;
                    log.debug("最后分块过小({}字符)，与前一个分块合并", chunkContent.length());
                }

                DocumentChunk chunk = new DocumentChunk(
                        UUID.randomUUID().toString(),
                        "",                             // documentId，后续设置
                        "",                             // knowledgeBaseId，后续设置
                        chunkContent,
                        Optional.empty(),               // contextPrefix
                        chunkIndex,
                        position,
                        end,
                        tokenCounter.countTokens(chunkContent),
                        TextUtils.sha256(chunkContent),
                        List.of(),                      // headingHierarchy
                        0,                              // pageNumber
                        metadata
                );
                chunks.add(chunk);
                chunkIndex++;
                log.debug("生成分块 #{}: startOffset={}, endOffset={}, 长度={}", chunkIndex - 1, position, end, chunkContent.length());
            }

            // 当前块已覆盖到文本末尾时，不再回溯 — 避免 overlap 产生无意义的尾部碎片
            if (end >= textLength) {
                break;
            }

            // 下一个分块起始位置（考虑重叠）
            int nextPosition = end - config.overlapSize();
            if (nextPosition <= position) {
                // 防止无限循环：确保位置前进
                nextPosition = end;
            }
            position = nextPosition;
        }

        log.debug("分块完成: 共生成 {} 个分块", chunks.size());
        return List.copyOf(chunks);
    }

    @Override
    public int estimateChunkCount(int textLength) {
        if (textLength <= 0) {
            return 0;
        }
        int effectiveStep = config.maxChunkSize() - config.overlapSize();
        if (effectiveStep <= 0) {
            // overlapSize >= maxChunkSize 时退化为逐字符前进，按 maxChunkSize 估算
            effectiveStep = config.maxChunkSize();
        }
        return Math.max(1, (int) Math.ceil((double) textLength / effectiveStep));
    }

    @Override
    public String strategyName() {
        return "fixed-size";
    }

    /**
     * 从 end 向前分层搜索最佳句子边界。
     *
     * <p>搜索优先级：段落分隔（\n\n）→ 强句子边界（。！？.!?）→ 弱边界（；;\n）。
     * 高优先级命中后立即返回，不继续搜索低优先级。</p>
     *
     * @param text  原始文本
     * @param start 搜索起始位置
     * @param end   搜索结束位置
     * @return 句子结束位置（标点后一个字符），未找到则返回 end
     */
    private int findNearestSentenceEnd(String text, int start, int end) {
        int minPos = start + config.minChunkSize();

        // 1. 优先找段落分隔（\n\n）
        int paraBreak = text.lastIndexOf("\n\n", end - 1);
        if (paraBreak >= minPos) {
            return paraBreak + 2; // 跳过 \n\n
        }

        // 2. 找强句子边界（。！？.!?）
        for (int i = end - 1; i >= minPos; i--) {
            if (STRONG_BOUNDARIES.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }

        // 3. 找弱边界（；;\n）
        for (int i = end - 1; i >= minPos; i--) {
            if (WEAK_BOUNDARIES.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }

        return end;
    }

}
