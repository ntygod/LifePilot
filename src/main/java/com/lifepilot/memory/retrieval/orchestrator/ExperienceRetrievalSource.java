package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 经验检索源 — 遍历 L3 EXPERIENCE 实体做简单文本匹配评分。
 *
 * <p>本 spec 不做向量检索（HybridRetrievalSource 已覆盖）；此适配器仅对 description/name
 * 中包含 query 关键词的经验实体做简单加权，避免与 hybrid 结果完全重复。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class ExperienceRetrievalSource implements SourceAdapter {

    private final SemanticMemory semanticMemory;

    public ExperienceRetrievalSource(SemanticMemory semanticMemory) {
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
    }

    @Override
    public String name() { return "experience"; }

    @Override
    public List<EvidenceItem> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("检索 source topK 必须大于 0: " + topK);
        }
        List<TemporalEntity> all = Objects.requireNonNull(
                semanticMemory.findCurrentByType(EntityType.EXPERIENCE),
                "SemanticMemory 返回经验列表不能为空");
        String q = query.toLowerCase();
        return all.stream()
                .peek(ExperienceRetrievalSource::requireExperience)
                .map(e -> scoreEntity(e, q))
                .filter(s -> s.score() > 0.0f)
                .sorted(Comparator.comparingDouble(EvidenceItem::score).reversed())
                .limit(topK)
                .toList();
    }

    private EvidenceItem scoreEntity(TemporalEntity entity, String qLower) {
        String name = entity.name().toLowerCase();
        String desc = entity.description().toLowerCase();
        // 简单 token 命中计数 → 归一
        float score = 0.0f;
        for (String token : qLower.split("\\s+")) {
            if (token.isBlank()) continue;
            if (name.contains(token)) score += 0.5f;
            if (desc.contains(token)) score += 0.3f;
        }
        score = Math.min(1.0f, score);
        return new EvidenceItem(
                entity.id(),
                EntityType.EXPERIENCE.name(),
                entity.name(),
                entity.description(),
                score,
                "experience:text",
                entity.importanceScore(),
                Map.of("accessCount", entity.accessCount())
        );
    }

    private static void requireExperience(TemporalEntity entity) {
        if (entity == null) {
            throw new IllegalStateException("经验检索源返回 null 实体");
        }
        requireCanonicalText(entity.id(), "经验检索实体 ID");
        if (entity.type() != EntityType.EXPERIENCE) {
            throw new IllegalStateException("经验检索源返回非 EXPERIENCE 实体: " + entity.id());
        }
        requireCanonicalText(entity.name(), "经验检索实体名称");
        requireCanonicalText(entity.description(), "经验检索实体描述");
        if (!Float.isFinite(entity.importanceScore())
                || entity.importanceScore() < 0.0f
                || entity.importanceScore() > 1.0f) {
            throw new IllegalStateException("经验检索实体 importanceScore 必须在 [0,1] 范围内: "
                    + entity.id());
        }
    }

    private static void requireCanonicalText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + "不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalStateException(label + "不能包含首尾空白: " + value);
        }
    }
}
