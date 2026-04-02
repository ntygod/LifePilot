package com.lifepilot.skill.registry;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.skill.model.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.stream.Stream;

/**
 * Skill 语义搜索索引。
 *
 * <p>优先使用向量相似度匹配技能；当向量服务不可用或返回空向量时，
 * 自动降级为关键词匹配。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillSearchIndex.class);

    private final ConcurrentHashMap<String, float[]> embeddings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> skillTexts = new ConcurrentHashMap<>();
    @Nullable
    private final EmbeddingRouter embeddingRouter;
    @Nullable
    private final Executor asyncExecutor;
    @Nullable
    private final SkillEmbeddingCacheRepository cacheRepository;

    public SkillSearchIndex(@Nullable EmbeddingRouter embeddingRouter) {
        this(embeddingRouter, null, null);
    }

    public SkillSearchIndex(@Nullable EmbeddingRouter embeddingRouter,
                            @Nullable Executor asyncExecutor,
                            @Nullable SkillEmbeddingCacheRepository cacheRepository) {
        this.embeddingRouter = embeddingRouter;
        this.asyncExecutor = asyncExecutor;
        this.cacheRepository = cacheRepository;
    }

    public void index(SkillDefinition definition) {
        String text = buildSearchText(definition);
        String contentHash = sha256(text);
        skillTexts.put(definition.id(), normalizeText(text));

        if (embeddingRouter == null) {
            log.debug("Skill 关键词索引完成（降级模式）: skillId={}", definition.id());
            return;
        }

        // 优先从持久化缓存加载（内容未变则命中）
        if (cacheRepository != null) {
            float[] cached = cacheRepository.find(definition.id(), contentHash);
            if (cached != null) {
                embeddings.put(definition.id(), cached);
                log.debug("Skill 向量索引命中缓存: skillId={}", definition.id());
                return;
            }
        }

        // 缓存未命中，异步调用 embedding API
        if (asyncExecutor != null) {
            asyncExecutor.execute(() -> indexVector(definition.id(), text, contentHash));
        } else {
            indexVector(definition.id(), text, contentHash);
        }
    }

    private void indexVector(String skillId, String text, String contentHash) {
        try {
            float[] vector = embeddingRouter.embed(text, EmbeddingUseCase.DEFAULT, null, null);
            if (isEmptyVector(vector)) {
                log.warn("Skill 向量索引返回空向量，降级为关键词模式: skillId={}", skillId);
                return;
            }
            embeddings.put(skillId, vector);
            if (cacheRepository != null) {
                cacheRepository.save(skillId, contentHash, vector);
            }
            log.debug("Skill 向量索引完成: skillId={}", skillId);
        } catch (LlmUnavailableException e) {
            log.warn("Skill 向量索引失败，降级为关键词模式: skillId={}, error={}",
                    skillId, e.getMessage());
        }
    }

    public void remove(String skillId) {
        embeddings.remove(skillId);
        skillTexts.remove(skillId);
        if (cacheRepository != null) {
            cacheRepository.delete(skillId);
        }
        log.debug("Skill 索引已移除: skillId={}", skillId);
    }

    public int reindexAll() {
        if (embeddingRouter == null) {
            log.debug("EmbeddingRouter 不可用，跳过 Skill 向量重建");
            return 0;
        }

        // 收集需要重建的 skillId
        var pending = skillTexts.entrySet().stream()
                .filter(e -> !embeddings.containsKey(e.getKey()))
                .toList();

        if (pending.isEmpty()) {
            return 0;
        }

        // 异步重建，不阻塞调用方
        if (asyncExecutor != null) {
            int count = pending.size();
            log.info("Skill 向量异步重建启动: pending={}", count);
            for (var entry : pending) {
                asyncExecutor.execute(() -> rebuildSingle(entry.getKey(), entry.getValue()));
            }
            return count;
        }

        // 无异步执行器时同步重建
        int rebuilt = 0;
        for (var entry : pending) {
            if (rebuildSingle(entry.getKey(), entry.getValue())) {
                rebuilt++;
            }
        }
        return rebuilt;
    }

    private boolean rebuildSingle(String skillId, String text) {
        try {
            float[] vector = embeddingRouter.embed(text, EmbeddingUseCase.DEFAULT, null, null);
            if (isEmptyVector(vector)) {
                log.debug("Skill 向量重建返回空向量，跳过: skillId={}", skillId);
                return false;
            }
            embeddings.put(skillId, vector);
            if (cacheRepository != null) {
                cacheRepository.save(skillId, sha256(text), vector);
            }
            log.debug("Skill 向量重建成功: skillId={}", skillId);
            return true;
        } catch (LlmUnavailableException e) {
            log.debug("Skill 向量重建失败: skillId={}, error={}", skillId, e.getMessage());
            return false;
        }
    }

    public List<SearchResult> search(String query, int topK) {
        if (embeddingRouter == null) {
            return keywordSearch(query, topK);
        }
        return vectorSearch(query, topK);
    }

    private List<SearchResult> vectorSearch(String query, int topK) {
        EmbeddingRouter router = this.embeddingRouter;
        if (router == null) {
            return keywordSearch(query, topK);
        }
        String normalizedQuery = expandTerms(query);
        if (normalizedQuery.isBlank()) {
            return List.of();
        }

        float[] queryVector;
        try {
            queryVector = router.embed(normalizedQuery, EmbeddingUseCase.DEFAULT, null, null);
        } catch (LlmUnavailableException e) {
            log.warn("Skill 查询向量生成失败，降级为关键词搜索: query={}, error={}", query, e.getMessage());
            return keywordSearch(query, topK);
        }
        if (isEmptyVector(queryVector)) {
            log.warn("Skill 查询向量为空，降级为关键词搜索: query={}", query);
            return keywordSearch(query, topK);
        }

        List<SearchResult> results = new ArrayList<>();
        embeddings.forEach((skillId, vector) -> {
            if (isEmptyVector(vector)) {
                return;
            }
            double similarity = cosineSimilarity(queryVector, vector);
            if (similarity > 0.5) {
                results.add(new SearchResult(skillId, similarity));
            }
        });

        results.sort(Comparator.comparingDouble(SearchResult::similarity).reversed());
        if (results.size() > topK) {
            return List.copyOf(results.subList(0, topK));
        }
        return List.copyOf(results);
    }

    private List<SearchResult> keywordSearch(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalizedQuery = normalizeText(query);
        if (normalizedQuery.isBlank()) {
            return List.of();
        }
        String[] keywords = normalizedQuery.split("\\s+");

        List<SearchResult> results = new ArrayList<>();
        skillTexts.forEach((skillId, text) -> {
            int hits = 0;
            for (String keyword : keywords) {
                if (!keyword.isBlank() && text.contains(keyword)) {
                    hits++;
                }
            }
            if (hits > 0) {
                double relevance = (double) hits / keywords.length;
                results.add(new SearchResult(skillId, relevance));
            }
        });

        results.sort(Comparator.comparingDouble(SearchResult::similarity).reversed());
        if (results.size() > topK) {
            return List.copyOf(results.subList(0, topK));
        }
        return List.copyOf(results);
    }

    static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) {
            return 0.0;
        }
        return dot / denominator;
    }

    private static boolean isEmptyVector(@Nullable float[] vector) {
        return vector == null || vector.length == 0;
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 在所有 JVM 实现中都是必须支持的
            throw new AssertionError(e);
        }
    }

    private String buildSearchText(SkillDefinition definition) {
        return Stream.of(
                        definition.id(),
                        definition.name(),
                        definition.description(),
                        String.join(" ", definition.triggers())
                )
                .filter(part -> part != null && !part.isBlank())
                .map(SkillSearchIndex::expandTerms)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static String normalizeText(String text) {
        return expandTerms(text).toLowerCase(Locale.ROOT);
    }

    private static String expandTerms(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public record SearchResult(String skillId, double similarity) {
    }
}
