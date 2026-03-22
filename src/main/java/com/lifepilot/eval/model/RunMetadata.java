package com.lifepilot.eval.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 评估运行元数据 — 记录运行时的模型、Prompt 版本、配置快照等上下文信息。
 *
 * @param modelId        使用的 LLM 模型标识
 * @param promptVersion  Prompt 模板版本/哈希
 * @param configSnapshot 评估配置快照（JSON）
 * @param baselineRunId  对比基线运行 ID
 * @param labels         自定义标签
 * @author zsg
 * @since 2026-03-22
 */
public record RunMetadata(
        @Nullable String modelId,
        @Nullable String promptVersion,
        @Nullable String configSnapshot,
        @Nullable String baselineRunId,
        Map<String, String> labels
) {
    public RunMetadata {
        labels = labels != null ? Map.copyOf(labels) : Map.of();
    }
}
