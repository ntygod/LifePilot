package com.lifepilot.skill.registry;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.skill.model.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

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

    public SkillSearchIndex(@Nullable EmbeddingRouter embeddingRouter) {
        this.embeddingRouter = embeddingRouter;
    }

    public void index(SkillDefinition definition) {
        String text = definition.name() + " " + definition.description();
        skillTexts.put(definition.id(), text.toLowerCase(Locale.ROOT));

        if (embeddingRouter == null) {
            log.debug("Skill 关键词索引完成（降级模式）: skillId={}", definition.id());
            return;
        }

        try {
            float[] vector = embeddingRouter.embed(text, EmbeddingUseCase.DEFAULT, null, null);
            if (isEmptyVector(vector)) {
                log.warn("Skill 向量索引返回空向量，降级为关键词模式: skillId={}", definition.id());
                return;
            }
            embeddings.put(definition.id(), vector);
            log.debug("Skill 向量索引完成: skillId={}", definition.id());
        } catch (LlmUnavailableException e) {
            log.warn("Skill 向量索引失败，降级为关键词模式: skillId={}, error={}",
                    definition.id(), e.getMessage());
        }
    }

    public void remove(String skillId) {
        embeddings.remove(skillId);
        skillTexts.remove(skillId);
        log.debug("Skill 索引已移除: skillId={}", skillId);
    }

    public int reindexAll() {
        if (embeddingRouter == null) {
            log.debug("EmbeddingRouter 不可用，跳过 Skill 向量重建");
            return 0;
        }

        int rebuilt = 0;
        for (var entry : skillTexts.entrySet()) {
            String skillId = entry.getKey();
            if (embeddings.containsKey(skillId)) {
                continue;
            }
            try {
                float[] vector = embeddingRouter.embed(entry.getValue(), EmbeddingUseCase.DEFAULT, null, null);
                if (isEmptyVector(vector)) {
                    log.debug("Skill 向量重建返回空向量，跳过: skillId={}", skillId);
                    continue;
                }
                embeddings.put(skillId, vector);
                rebuilt++;
                log.debug("Skill 向量重建成功: skillId={}", skillId);
            } catch (LlmUnavailableException e) {
                log.debug("Skill 向量重建失败: skillId={}, error={}", skillId, e.getMessage());
            }
        }
        if (rebuilt > 0) {
            log.info("Skill 向量重建完成: success={}, total={}", rebuilt, skillTexts.size());
        }
        return rebuilt;
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

        float[] queryVector;
        try {
            queryVector = router.embed(query, EmbeddingUseCase.DEFAULT, null, null);
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
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        String[] keywords = lowerQuery.split("\\s+");

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

    public record SearchResult(String skillId, double similarity) {
    }
}
