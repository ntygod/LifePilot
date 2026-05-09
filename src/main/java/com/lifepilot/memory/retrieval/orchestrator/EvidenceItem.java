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
 * @param score       分数 [0, 1]（各源自行归一化）
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
        entityType = Objects.requireNonNullElse(entityType, "");
        name = Objects.requireNonNullElse(name, "");
        sourcePath = Objects.requireNonNull(sourcePath, "sourcePath 不能为空");
        score = clamp(score);
        confidence = clamp(confidence);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
