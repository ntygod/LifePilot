package com.lifepilot.knowledge.chunking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    /** 句子结束标点集合（中文 + 英文 + 换行） */
    private static final String SENTENCE_ENDINGS = "。！？；.!?\n";

    private final ChunkingConfig config;

    /**
     * 构造固定大小分块器。
     *
     * @param config 分块配置
     */
    public FixedSizeChunker(ChunkingConfig config) {
        this.config = config;
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
                        estimateTokens(chunkContent),
                        sha256(chunkContent),
                        List.of(),                      // headingHierarchy
                        0,                              // pageNumber
                        metadata
                );
                chunks.add(chunk);
                chunkIndex++;
                log.debug("生成分块 #{}: startOffset={}, endOffset={}, 长度={}", chunkIndex - 1, position, end, chunkContent.length());
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
     * 从 end 向前搜索最近的句子结束标点。
     *
     * @param text  原始文本
     * @param start 搜索起始位置
     * @param end   搜索结束位置
     * @return 句子结束位置（标点后一个字符），未找到则返回 end
     */
    private int findNearestSentenceEnd(String text, int start, int end) {
        for (int i = end - 1; i >= start + config.minChunkSize(); i--) {
            if (SENTENCE_ENDINGS.indexOf(text.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return end;
    }

    /**
     * 估算文本的 Token 数量。
     *
     * <p>中文字符按 1 Token/字符计算，其他字符按 4 字符/Token 计算。
     *
     * @param text 文本内容
     * @return 估算 Token 数
     */
    private static int estimateTokens(String text) {
        long chineseChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - chineseChars;
        return (int) (chineseChars + otherChars / 4);
    }

    /**
     * 计算文本的 SHA-256 哈希值。
     *
     * @param text 文本内容
     * @return 小写十六进制哈希字符串（64 字符）
     */
    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须支持的算法，不应发生
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
