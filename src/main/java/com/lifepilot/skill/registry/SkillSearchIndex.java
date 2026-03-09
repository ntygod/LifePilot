package com.lifepilot.skill.registry;

import com.lifepilot.llm.LlmRouter;
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
 * Skill 语义搜索索引 — 基于 Embedding 向量的语义匹配。
 *
 * <p>使用 {@link ConcurrentHashMap} 缓存每个 Skill 的 Embedding 向量，
 * 通过余弦相似度进行语义搜索。LLM 不可用时降级为关键词匹配模式，
 * 基于 Skill 名称和描述进行简单文本匹配。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillSearchIndex.class);

    private final ConcurrentHashMap<String, float[]> embeddings = new ConcurrentHashMap<>();
    /** 关键词降级模式使用：缓存每个 Skill 的名称+描述文本（小写） */
    private final ConcurrentHashMap<String, String> skillTexts = new ConcurrentHashMap<>();
    @Nullable
    private final LlmRouter llmRouter;

    public SkillSearchIndex(@Nullable LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    /**
     * 为 Skill 生成 Embedding 并缓存。
     *
     * <p>使用 Skill 的 name + description 生成 Embedding 向量。
     * LLM 不可用时降级为缓存文本用于关键词匹配。</p>
     *
     * @param definition Skill 定义
     */
    public void index(SkillDefinition definition) {
        String text = definition.name() + " " + definition.description();
        // 始终缓存文本，供关键词降级模式使用
        skillTexts.put(definition.id(), text.toLowerCase(Locale.ROOT));

        if (llmRouter == null) {
            log.debug("Skill 关键词索引成功（降级模式）: skillId={}", definition.id());
            return;
        }
        try {
            float[] vector = llmRouter.embed(text);
            embeddings.put(definition.id(), vector);
            log.debug("Skill Embedding 索引成功: skillId={}", definition.id());
        } catch (LlmUnavailableException e) {
            log.warn("Embedding 生成失败，降级为关键词索引: skillId={}, 原因={}", definition.id(), e.getMessage());
        }
    }

    /**
     * 移除索引缓存。
     *
     * @param skillId Skill ID
     */
    public void remove(String skillId) {
        embeddings.remove(skillId);
        skillTexts.remove(skillId);
        log.debug("Skill 索引已移除: skillId={}", skillId);
    }

    /**
     * 搜索 Skill，返回 Top-K 结果。
     *
     * <p>当 LlmRouter 可用时，使用向量语义搜索（余弦相似度 &gt; 0.5）。
     * 当 LlmRouter 不可用时，降级为关键词匹配模式，基于查询词在
     * Skill 名称和描述中的命中率计算相关度。</p>
     *
     * @param query 查询文本
     * @param topK  最大返回数量
     * @return 搜索结果列表，按相关度降序排列
     */
    public List<SearchResult> search(String query, int topK) {
        if (llmRouter == null) {
            return keywordSearch(query, topK);
        }
        return vectorSearch(query, topK);
    }

    /**
     * 向量语义搜索（LlmRouter 可用时使用）。
     */
    private List<SearchResult> vectorSearch(String query, int topK) {
        LlmRouter router = this.llmRouter;
        if (router == null) {
            return keywordSearch(query, topK);
        }
        float[] queryVector;
        try {
            queryVector = router.embed(query);
        } catch (LlmUnavailableException e) {
            log.warn("查询 Embedding 生成失败，降级为关键词搜索: query={}, 原因={}", query, e.getMessage());
            return keywordSearch(query, topK);
        }

        List<SearchResult> results = new ArrayList<>();
        embeddings.forEach((skillId, vector) -> {
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

    /**
     * 关键词匹配搜索（降级模式）。
     *
     * <p>将查询文本按空格分词，计算每个 Skill 文本中命中的关键词比例作为相关度。
     * 过滤相关度 &gt; 0 的结果，按相关度降序排列。</p>
     */
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

    /**
     * 计算两个向量的余弦相似度。
     *
     * <p>公式：dot(a, b) / (norm(a) * norm(b))</p>
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 余弦相似度，范围 [-1, 1]
     */
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

    /**
     * 语义搜索结果。
     *
     * @param skillId    匹配的 Skill ID
     * @param similarity 余弦相似度
     */
    public record SearchResult(String skillId, double similarity) {}
}
