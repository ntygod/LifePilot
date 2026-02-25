package com.lifepilot.skill.registry;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.skill.model.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 语义搜索索引 — 基于 Embedding 向量的语义匹配。
 *
 * <p>使用 {@link ConcurrentHashMap} 缓存每个 Skill 的 Embedding 向量，
 * 通过余弦相似度进行语义搜索。LLM 不可用时降级跳过索引，
 * Skill 仍可通过 ID 精确查找。</p>
 *
 * @author zsg
 * @since 2026-07-28
 */
public class SkillSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(SkillSearchIndex.class);

    private final ConcurrentHashMap<String, float[]> embeddings = new ConcurrentHashMap<>();
    private final LlmRouter llmRouter;

    public SkillSearchIndex(LlmRouter llmRouter) {
        this.llmRouter = llmRouter;
    }

    /**
     * 为 Skill 生成 Embedding 并缓存。
     *
     * <p>使用 Skill 的 name + description 生成 Embedding 向量。
     * LLM 不可用时记录 WARN 日志并跳过索引。</p>
     *
     * @param definition Skill 定义
     */
    public void index(SkillDefinition definition) {
        String text = definition.name() + " " + definition.description();
        try {
            float[] vector = llmRouter.embed(text);
            embeddings.put(definition.id(), vector);
            log.debug("Skill Embedding 索引成功: skillId={}", definition.id());
        } catch (LlmUnavailableException e) {
            log.warn("Embedding 生成失败，跳过索引: skillId={}, 原因={}", definition.id(), e.getMessage());
        }
    }

    /**
     * 移除 Embedding 缓存。
     *
     * @param skillId Skill ID
     */
    public void remove(String skillId) {
        embeddings.remove(skillId);
        log.debug("Skill Embedding 索引已移除: skillId={}", skillId);
    }

    /**
     * 语义搜索，返回相似度 &gt; 0.5 的 Top-K 结果。
     *
     * <p>将查询文本转换为 Embedding 向量，计算与所有缓存向量的余弦相似度，
     * 过滤相似度大于 0.5 的结果，按相似度降序排列，限制返回数量为 topK。</p>
     *
     * <p>LLM 不可用时返回空列表并记录 WARN 日志。</p>
     *
     * @param query 查询文本
     * @param topK  最大返回数量
     * @return 搜索结果列表，按相似度降序排列
     */
    public List<SearchResult> search(String query, int topK) {
        float[] queryVector;
        try {
            queryVector = llmRouter.embed(query);
        } catch (LlmUnavailableException e) {
            log.warn("查询 Embedding 生成失败，返回空结果: query={}, 原因={}", query, e.getMessage());
            return List.of();
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
