package com.lifepilot.knowledge.chunking;

import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 语义分块器 — 基于 Embedding 余弦相似度检测语义断点，在语义边界处切分文档。
 *
 * <p>算法流程：
 * <ol>
 *   <li>按句子边界切分文本</li>
 *   <li>对每个句子调用 Embedding 获取向量</li>
 *   <li>使用滑动窗口平滑计算相邻句子余弦相似度</li>
 *   <li>相似度低于阈值处标记为语义断点</li>
 *   <li>合并过小分块、二次切分过大分块</li>
 * </ol>
 *
 * <p>Embedding 不可用时降级到 {@link RecursiveChunker}。
 *
 * @author zsg
 * @since 2026-03-06
 */
public non-sealed class SemanticChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunker.class);

    /** 句子边界正则：中英文句号、问号、感叹号、换行符后切分 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
            "(?<=[。！？.!?\\n])\\s*");

    private final LlmRouter llmRouter;
    private final RecursiveChunker fallbackChunker;
    private final KnowledgeBaseProperties.Chunking.SemanticChunking config;

    /**
     * 构造语义分块器。
     *
     * @param llmRouter       LLM 路由器（用于 Embedding）
     * @param fallbackChunker 降级分块器
     * @param config          语义分块配置
     */
    public SemanticChunker(LlmRouter llmRouter,
                           RecursiveChunker fallbackChunker,
                           KnowledgeBaseProperties.Chunking.SemanticChunking config) {
        this.llmRouter = llmRouter;
        this.fallbackChunker = fallbackChunker;
        this.config = config;
        log.debug("初始化 SemanticChunker: breakpointThreshold={}, bufferSize={}, minChunkSize={}, maxChunkSize={}",
                config.breakpointThreshold(), config.bufferSize(), config.minChunkSize(), config.maxChunkSize());
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        try {
            // 1. 按句子边界切分
            List<String> sentences = splitSentences(text);
            if (sentences.size() <= 1) {
                return buildChunks(List.of(text), metadata);
            }

            // 2. 对每个句子获取 Embedding 向量
            List<float[]> embeddings = computeEmbeddings(sentences);

            // 3. 计算相邻句子余弦相似度（滑动窗口平滑）
            List<Double> similarities = smoothSimilarities(embeddings);

            // 4. 检测语义断点
            List<Integer> breakpoints = detectBreakpoints(similarities);

            // 5. 按断点切分句子为分块
            List<String> rawChunks = splitByBreakpoints(sentences, breakpoints);

            // 6. 合并过小分块、二次切分过大分块
            List<String> merged = mergeSmallChunks(rawChunks);
            List<String> finalChunks = splitLargeChunks(merged);

            log.debug("语义分块完成: 句子数={}, 断点数={}, 最终分块数={}, 平均分块大小={}",
                    sentences.size(), breakpoints.size(), finalChunks.size(),
                    finalChunks.stream().mapToInt(String::length).average().orElse(0));

            return buildChunks(finalChunks, metadata);

        } catch (LlmUnavailableException e) {
            log.warn("Embedding 不可用，降级到 RecursiveChunker: {}", e.getMessage());
            return fallbackChunker.chunk(text, metadata);
        }
    }

    @Override
    public int estimateChunkCount(int textLength) {
        if (textLength <= 0) return 0;
        int avgChunkSize = (config.minChunkSize() + config.maxChunkSize()) / 2;
        return Math.max(1, (int) Math.ceil((double) textLength / avgChunkSize));
    }

    @Override
    public String strategyName() {
        return "semantic";
    }

    /**
     * 按句子边界切分文本。
     */
    private List<String> splitSentences(String text) {
        String[] parts = SENTENCE_BOUNDARY.split(text);
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    /**
     * 对每个句子调用 Embedding 获取向量。
     *
     * @throws LlmUnavailableException Embedding 不可用时抛出
     */
    private List<float[]> computeEmbeddings(List<String> sentences) {
        List<float[]> embeddings = new ArrayList<>(sentences.size());
        for (String sentence : sentences) {
            embeddings.add(llmRouter.embed(sentence));
        }
        return embeddings;
    }

    /**
     * 使用滑动窗口平滑计算相邻句子余弦相似度。
     *
     * <p>对每个位置 i，取 [i-bufferSize, i+bufferSize] 范围内 Embedding 的均值作为该位置的向量表示，
     * 然后计算相邻位置的余弦相似度。
     */
    private List<Double> smoothSimilarities(List<float[]> embeddings) {
        int n = embeddings.size();
        if (n <= 1) return List.of();

        int dim = embeddings.getFirst().length;
        int buffer = config.bufferSize();

        // 计算每个位置的平滑向量
        List<float[]> smoothed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            float[] avg = new float[dim];
            int start = Math.max(0, i - buffer);
            int end = Math.min(n - 1, i + buffer);
            int count = end - start + 1;
            for (int j = start; j <= end; j++) {
                float[] vec = embeddings.get(j);
                for (int d = 0; d < dim; d++) {
                    avg[d] += vec[d];
                }
            }
            for (int d = 0; d < dim; d++) {
                avg[d] /= count;
            }
            smoothed.add(avg);
        }

        // 计算相邻平滑向量的余弦相似度
        List<Double> similarities = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            similarities.add(cosineSimilarity(smoothed.get(i), smoothed.get(i + 1)));
        }
        return similarities;
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }

    /**
     * 检测语义断点：相似度低于阈值的位置。
     *
     * @return 断点索引列表（表示在该句子之后切分）
     */
    private List<Integer> detectBreakpoints(List<Double> similarities) {
        List<Integer> breakpoints = new ArrayList<>();
        for (int i = 0; i < similarities.size(); i++) {
            if (similarities.get(i) < config.breakpointThreshold()) {
                breakpoints.add(i);
            }
        }
        return breakpoints;
    }

    /**
     * 按断点将句子列表切分为文本分块。
     */
    private List<String> splitByBreakpoints(List<String> sentences, List<Integer> breakpoints) {
        List<String> chunks = new ArrayList<>();
        Set<Integer> bpSet = new HashSet<>(breakpoints);
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < sentences.size(); i++) {
            if (!current.isEmpty()) {
                current.append(" ");
            }
            current.append(sentences.get(i));

            if (bpSet.contains(i) || i == sentences.size() - 1) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
        }
        return chunks;
    }

    /**
     * 合并过小分块（字符数 < minChunkSize）。
     */
    private List<String> mergeSmallChunks(List<String> chunks) {
        if (chunks.size() <= 1) return chunks;

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder(chunks.getFirst());

        for (int i = 1; i < chunks.size(); i++) {
            if (current.length() < config.minChunkSize()) {
                current.append(" ").append(chunks.get(i));
            } else {
                result.add(current.toString());
                current = new StringBuilder(chunks.get(i));
            }
        }
        // 最后一段如果太小，与前一段合并
        if (!current.isEmpty()) {
            if (current.length() < config.minChunkSize() && !result.isEmpty()) {
                String prev = result.removeLast();
                result.add(prev + " " + current);
            } else {
                result.add(current.toString());
            }
        }
        return result;
    }

    /**
     * 二次切分过大分块（字符数 > maxChunkSize）。
     */
    private List<String> splitLargeChunks(List<String> chunks) {
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() <= config.maxChunkSize()) {
                result.add(chunk);
            } else {
                // 按 maxChunkSize 硬切
                for (int i = 0; i < chunk.length(); i += config.maxChunkSize()) {
                    result.add(chunk.substring(i, Math.min(i + config.maxChunkSize(), chunk.length())));
                }
            }
        }
        return result;
    }

    /**
     * 将文本分块列表构建为 {@link DocumentChunk} 列表。
     */
    private List<DocumentChunk> buildChunks(List<String> textChunks, Map<String, String> metadata) {
        List<DocumentChunk> result = new ArrayList<>(textChunks.size());
        int offset = 0;

        for (int i = 0; i < textChunks.size(); i++) {
            String content = textChunks.get(i);
            result.add(new DocumentChunk(
                    UUID.randomUUID().toString(),
                    "",     // documentId，后续由调用方设置
                    "",     // knowledgeBaseId，后续由调用方设置
                    content,
                    Optional.empty(),
                    i,
                    offset,
                    offset + content.length(),
                    estimateTokens(content),
                    sha256(content),
                    List.of(),
                    0,
                    metadata
            ));
            offset += content.length();
        }
        return List.copyOf(result);
    }

    /**
     * 估算 Token 数量（中文字符按 1 Token，英文按 4 字符 1 Token）。
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
