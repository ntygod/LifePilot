package com.lifepilot.memory.retrieval.orchestrator;

import org.springframework.lang.Nullable;

import java.util.Map;
import java.util.Objects;

/**
 * 统一检索结果 item — 各 SourceAdapter 返回的最小数据单元。
 *
 * @param entityId    实体 id（或 KB 文档 chunk id）
 * @param entityType  实体类型（LLM 可读字符串）
 * @param name        名称
 * @param description 描述
 * @param score       非负排序分（由各源定义，可不是概率）
 * @param sourcePath  来源路径（如 "vector" / "fts" / "experience" / "knowledge-base"）
 * @param confidence  置信度 [0, 1]
 * @param metadata    自由元数据
 * @author zsg
 * @since 2026-05-09
 */
public record EvidenceItem(
        String entityId,
        String entityType,
        String name,
        @Nullable String description,
        float score,
        String sourcePath,
        float confidence,
        Map<String, Object> metadata
) {

    public EvidenceItem {
        entityId = Objects.requireNonNull(entityId, "entityId 不能为空");
        entityType = Objects.requireNonNull(entityType, "entityType 不能为空");
        name = Objects.requireNonNull(name, "name 不能为空");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath 不能为空");
        if (!(score >= 0.0f) || Float.isInfinite(score)) {
            throw new IllegalArgumentException("score 必须是非负有限数: " + score);
        }
        requireConfidence(confidence);
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata 不能为空"));
    }

    private static void requireConfidence(float value) {
        if (!(value >= 0.0f && value <= 1.0f)) {
            throw new IllegalArgumentException("confidence 必须在 [0,1] 范围内: " + value);
        }
    }
}
