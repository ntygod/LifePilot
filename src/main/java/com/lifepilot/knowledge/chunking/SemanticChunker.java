package com.lifepilot.knowledge.chunking;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.knowledge.util.TextUtils;
import com.lifepilot.knowledge.util.TokenCounter;
import com.lifepilot.llm.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 语义分块器，基于 Embedding 余弦相似度检测语义断点，在语义边界处切分文档。
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
 * <p>Embedding 不可用时降级到 {@link RecursiveChunker}。</p>
 *
 * @author zsg
 * @since 2026-03-06
 */
public non-sealed class SemanticChunker implements ChunkingStrategy {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunker.class);
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？.!?\\n])\\s*");

    private final EmbeddingRouter embeddingRouter;
    private final RecursiveChunker fallbackChunker;
    private final KnowledgeBaseProperties.Chunking.SemanticChunking config;
    private final TokenCounter tokenCounter;

    public SemanticChunker(EmbeddingRouter embeddingRouter,
                           RecursiveChunker fallbackChunker,
                           KnowledgeBaseProperties.Chunking.SemanticChunking config,
                           TokenCounter tokenCounter) {
        this.embeddingRouter = embeddingRouter;
        this.fallbackChunker = fallbackChunker;
        this.config = config;
        this.tokenCounter = tokenCounter;
        log.debug("初始化 SemanticChunker: breakpointThreshold={}, bufferSize={}, minChunkSize={}, maxChunkSize={}",
                config.breakpointThreshold(), config.bufferSize(), config.minChunkSize(), config.maxChunkSize());
    }

    @Override
    public List<DocumentChunk> chunk(String text, Map<String, String> metadata) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        try {
            List<String> sentences = splitSentences(text);
            if (sentences.size() <= 1) {
                return buildChunks(List.of(text), metadata);
            }

            List<float[]> embeddings = computeEmbeddings(sentences);
            List<Double> similarities = smoothSimilarities(embeddings);
            List<Integer> breakpoints = detectBreakpoints(similarities);
            List<String> rawChunks = splitByBreakpoints(sentences, breakpoints);
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

    private List<float[]> computeEmbeddings(List<String> sentences) {
        float[][] batch = embeddingRouter.embedBatch(sentences, EmbeddingUseCase.KNOWLEDGE_BASE, null, null);
        return Arrays.asList(batch);
    }

    private List<Double> smoothSimilarities(List<float[]> embeddings) {
        int n = embeddings.size();
        if (n <= 1) return List.of();

        int dim = embeddings.getFirst().length;
        int buffer = config.bufferSize();

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

        List<Double> similarities = new ArrayList<>(n - 1);
        for (int i = 0; i < n - 1; i++) {
            similarities.add(cosineSimilarity(smoothed.get(i), smoothed.get(i + 1)));
        }
        return similarities;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }

    private List<Integer> detectBreakpoints(List<Double> similarities) {
        List<Integer> breakpoints = new ArrayList<>();
        for (int i = 0; i < similarities.size(); i++) {
            if (similarities.get(i) < config.breakpointThreshold()) {
                breakpoints.add(i);
            }
        }
        return breakpoints;
    }

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
     * 对超长分块委托 fallbackChunker 做句子级二次切分，保持语义完整性。
     */
    private List<String> splitLargeChunks(List<String> chunks) {
        List<String> result = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.length() <= config.maxChunkSize()) {
                result.add(chunk);
            } else {
                // 委托 RecursiveChunker 按句子边界切分，而非硬切字符
                List<DocumentChunk> subChunks = fallbackChunker.chunk(chunk, Map.of());
                for (DocumentChunk sub : subChunks) {
                    result.add(sub.content());
                }
            }
        }
        return result;
    }

    private List<DocumentChunk> buildChunks(List<String> textChunks, Map<String, String> metadata) {
        List<DocumentChunk> result = new ArrayList<>(textChunks.size());
        int offset = 0;

        for (int i = 0; i < textChunks.size(); i++) {
            String content = textChunks.get(i);
            result.add(new DocumentChunk(
                    UUID.randomUUID().toString(),
                    "",
                    "",
                    content,
                    Optional.empty(),
                    i,
                    offset,
                    offset + content.length(),
                    tokenCounter.countTokens(content),
                    TextUtils.sha256(content),
                    List.of(),
                    0,
                    metadata
            ));
            offset += content.length();
        }
        return List.copyOf(result);
    }
}
