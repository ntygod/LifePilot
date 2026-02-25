package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 递归语义分块器 — 在自然边界处递归切分。
 *
 * <p>分隔符优先级：段落（\n\n）→ 句子（。！？.!?）→ 词（空格/中文字符边界）。
 * 递归逻辑：尝试当前分隔符切分 → 子块超过 maxChunkSize 则用下一级分隔符继续切分。
 * 重叠通过回溯前一个分块的尾部 overlapSize 字符实现。
 *
 * @author zsg
 * @since 2026-02-25
 */
public non-sealed class RecursiveChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(RecursiveChunker.class);

    private final List<String> separators;
    private final int maxChunkSize;
    private final int minChunkSize;
    private final int overlapSize;

    /**
     * 构造递归语义分块器。
     *
     * @param config          通用分块配置
     * @param recursiveConfig 递归分块专用配置
     */
    public RecursiveChunker(ChunkingConfig config,
                            KnowledgeBaseProperties.Chunking.Recursive recursiveConfig) {
        this.separators = List.copyOf(recursiveConfig.separators());
        this.maxChunkSize = recursiveConfig.maxChunkSize();
        this.minChunkSize = recursiveConfig.minChunkSize();
        this.overlapSize = recursiveConfig.overlapSize();
        log.debug("初始化 RecursiveChunker: maxChunkSize={}, minChunkSize={}, overlapSize={}, separators={}",
                maxChunkSize, minChunkSize, overlapSize, separators.size());
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // 递归切分得到原始文本片段
        List<String> rawChunks = splitRecursively(text, 0);

        // 合并过小的片段
        List<String> merged = mergeSmallChunks(rawChunks);

        // 应用重叠并构建 DocumentChunk
        List<DocumentChunk> result = buildChunksWithOverlap(text, merged, metadata);

        log.debug("递归分块完成: 原始片段={}, 合并后={}, 最终分块={}",
                rawChunks.size(), merged.size(), result.size());
        return List.copyOf(result);
    }

    @Override
    public int estimateChunkCount(int textLength) {
        if (textLength <= 0) return 0;
        int effectiveStep = maxChunkSize - overlapSize;
        if (effectiveStep <= 0) effectiveStep = maxChunkSize;
        return Math.max(1, (int) Math.ceil((double) textLength / effectiveStep));
    }

    @Override
    public String strategyName() {
        return "recursive";
    }

    /**
     * 递归切分文本。
     *
     * @param text           待切分文本
     * @param separatorIndex 当前使用的分隔符索引
     * @return 切分后的文本片段列表
     */
    private List<String> splitRecursively(String text, int separatorIndex) {
        // 文本足够小，直接返回
        if (text.length() <= maxChunkSize) {
            return List.of(text);
        }

        // 所有分隔符都用完了，强制按 maxChunkSize 硬切
        if (separatorIndex >= separators.size()) {
            return hardSplit(text);
        }

        String separator = separators.get(separatorIndex);
        String[] parts = splitBySeparator(text, separator);

        // 如果分隔符无法切分（只有一个片段），尝试下一级分隔符
        if (parts.length <= 1) {
            return splitRecursively(text, separatorIndex + 1);
        }

        // 合并相邻片段使其不超过 maxChunkSize
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String part : parts) {
            if (current.isEmpty()) {
                current.append(part);
            } else if (current.length() + separator.length() + part.length() <= maxChunkSize) {
                current.append(separator).append(part);
            } else {
                // 当前累积的文本作为一个片段
                String accumulated = current.toString();
                if (accumulated.length() > maxChunkSize) {
                    // 仍然超过限制，递归用下一级分隔符切分
                    result.addAll(splitRecursively(accumulated, separatorIndex + 1));
                } else {
                    result.add(accumulated);
                }
                current = new StringBuilder(part);
            }
        }

        // 处理最后一个累积片段
        if (!current.isEmpty()) {
            String last = current.toString();
            if (last.length() > maxChunkSize) {
                result.addAll(splitRecursively(last, separatorIndex + 1));
            } else {
                result.add(last);
            }
        }

        return result;
    }

    /**
     * 按分隔符切分文本，保留分隔符在前一个片段末尾。
     */
    private String[] splitBySeparator(String text, String separator) {
        if (separator.equals(" ")) {
            // 空格分隔：同时处理中文字符边界
            return text.split("(?<=\\s)|(?=\\s)|(?<=[\u4e00-\u9fff])|(?=[\u4e00-\u9fff])");
        }
        // 使用 split 但保留非空片段
        String[] raw = text.split(java.util.regex.Pattern.quote(separator), -1);
        // 过滤空字符串
        return Arrays.stream(raw).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    /**
     * 强制按 maxChunkSize 硬切。
     */
    private List<String> hardSplit(String text) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += maxChunkSize) {
            result.add(text.substring(i, Math.min(i + maxChunkSize, text.length())));
        }
        return result;
    }

    /**
     * 合并过小的片段。
     */
    private List<String> mergeSmallChunks(List<String> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String chunk : chunks) {
            if (current.isEmpty()) {
                current.append(chunk);
            } else if (current.length() + chunk.length() <= maxChunkSize) {
                current.append(chunk);
            } else {
                result.add(current.toString());
                current = new StringBuilder(chunk);
            }
        }

        if (!current.isEmpty()) {
            String last = current.toString();
            // 最后一个片段太小则与前一个合并
            if (last.length() < minChunkSize && !result.isEmpty()) {
                String prev = result.removeLast();
                result.add(prev + last);
            } else {
                result.add(last);
            }
        }

        return result;
    }

    /**
     * 构建带重叠的 DocumentChunk 列表。
     */
    private List<DocumentChunk> buildChunksWithOverlap(String originalText,
                                                        List<String> textChunks,
                                                        Map<String, String> metadata) {
        List<DocumentChunk> result = new ArrayList<>();
        int chunkIndex = 0;

        for (int i = 0; i < textChunks.size(); i++) {
            String content = textChunks.get(i);

            // 应用重叠：从前一个分块尾部取 overlapSize 字符作为当前分块前缀
            if (i > 0 && overlapSize > 0) {
                String prevChunk = textChunks.get(i - 1);
                int overlapStart = Math.max(0, prevChunk.length() - overlapSize);
                String overlap = prevChunk.substring(overlapStart);
                content = overlap + content;
            }

            // 计算在原始文本中的偏移量（近似）
            int startOffset = findApproximateOffset(originalText, textChunks.get(i), i, textChunks);
            int endOffset = startOffset + textChunks.get(i).length();

            DocumentChunk chunk = new DocumentChunk(
                    UUID.randomUUID().toString(),
                    "",     // documentId，后续设置
                    "",     // knowledgeBaseId，后续设置
                    content,
                    Optional.empty(),
                    chunkIndex,
                    startOffset,
                    endOffset,
                    estimateTokens(content),
                    sha256(content),
                    List.of(),
                    0,
                    metadata
            );
            result.add(chunk);
            chunkIndex++;
        }

        return result;
    }

    /**
     * 近似查找文本片段在原始文本中的偏移量。
     */
    private int findApproximateOffset(String original, String chunk, int index,
                                       List<String> allChunks) {
        int offset = 0;
        for (int i = 0; i < index; i++) {
            offset += allChunks.get(i).length();
        }
        return Math.min(offset, original.length());
    }

    /**
     * 估算 Token 数量。
     */
    private static int estimateTokens(String text) {
        long chineseChars = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = text.length() - chineseChars;
        return (int) (chineseChars + otherChars / 4);
    }

    /**
     * 计算 SHA-256 哈希。
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
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
