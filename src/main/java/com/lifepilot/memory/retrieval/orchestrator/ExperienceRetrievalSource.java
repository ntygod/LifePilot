package com.lifepilot.memory.retrieval.orchestrator;

import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

    private static final Logger log = LoggerFactory.getLogger(ExperienceRetrievalSource.class);

    @Nullable
    private final SemanticMemory semanticMemory;

    public ExperienceRetrievalSource(@Nullable SemanticMemory semanticMemory) {
        this.semanticMemory = semanticMemory;
    }

    @Override
    public String name() { return "experience"; }

    @Override
    public boolean isAvailable() { return semanticMemory != null; }

    @Override
    public List<EvidenceItem> retrieve(String query, int topK) {
        if (semanticMemory == null || query == null || query.isBlank() || topK <= 0) {
            return List.of();
        }
        try {
            List<TemporalEntity> all = semanticMemory.findCurrentByType(EntityType.EXPERIENCE);
            String q = query.toLowerCase();
            return all.stream()
                    .map(e -> scoreEntity(e, q))
                    .filter(s -> s.score() > 0.0f)
                    .sorted(Comparator.comparingDouble(EvidenceItem::score).reversed())
                    .limit(topK)
                    .toList();
        } catch (Exception e) {
            log.debug("ExperienceRetrievalSource retrieve failed: {}", e.getMessage());
            return List.of();
        }
    }

    private EvidenceItem scoreEntity(TemporalEntity entity, String qLower) {
        String name = entity.name() == null ? "" : entity.name().toLowerCase();
        String desc = entity.description() == null ? "" : entity.description().toLowerCase();
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
}
