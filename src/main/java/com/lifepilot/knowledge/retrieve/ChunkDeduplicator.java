package com.lifepilot.knowledge.retrieve;

import com.lifepilot.knowledge.model.DocumentSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索结果去重器 — 基于 Jaccard trigram 相似度移除内容高度重叠的分块。
 *
 * <p>算法：按分数降序遍历结果，对每个候选检查与已保留结果的 trigram Jaccard 相似度。
 * 相似度超过阈值的低分结果被移除，保留分数最高的那个。
 * 时间复杂度 O(n²)，但 n 一般 < 50，性能无瓶颈。
 *
 * @author zsg
 * @since 2026-04-07
 */
public class ChunkDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(ChunkDeduplicator.class);

    private final double similarityThreshold;

    /**
     * 构造去重器。
     *
     * @param similarityThreshold Jaccard 相似度阈值（0~1），超过则视为重复。设为 0 禁用去重。
     */
    public ChunkDeduplicator(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    /**
     * 对检索结果去重，保留分数最高的，移除内容重叠度过高的低分结果。
     *
     * @param results 按分数降序排列的检索结果
     * @return 去重后的结果列表
     */
    public List<DocumentSearchResult> deduplicate(List<DocumentSearchResult> results) {
        if (similarityThreshold <= 0.0 || results.size() <= 1) {
            return results;
        }

        var kept = new ArrayList<DocumentSearchResult>();
        var keptTrigrams = new ArrayList<Set<String>>();

        for (var candidate : results) {
            Set<String> candidateTrigrams = extractTrigrams(candidate.content());

            boolean isDuplicate = false;
            for (var existingTrigrams : keptTrigrams) {
                if (jaccardSimilarity(candidateTrigrams, existingTrigrams) >= similarityThreshold) {
                    isDuplicate = true;
                    break;
                }
            }

            if (!isDuplicate) {
                kept.add(candidate);
                keptTrigrams.add(candidateTrigrams);
            }
        }

        if (kept.size() < results.size()) {
            log.debug("检索结果去重: 原始={}, 去重后={}, 移除={}",
                    results.size(), kept.size(), results.size() - kept.size());
        }
        return kept;
    }

    /**
     * 提取文本的所有 3 字符子串（trigram）集合。
     */
    private Set<String> extractTrigrams(String text) {
        if (text == null || text.length() < 3) {
            return Set.of();
        }
        var trigrams = new HashSet<String>();
        for (int i = 0; i <= text.length() - 3; i++) {
            trigrams.add(text.substring(i, i + 3));
        }
        return trigrams;
    }

    /**
     * 计算两个集合的 Jaccard 相似度：|A ∩ B| / |A ∪ B|。
     */
    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;

        int intersection = 0;
        for (var item : a) {
            if (b.contains(item)) intersection++;
        }
        int union = a.size() + b.size() - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }
}
