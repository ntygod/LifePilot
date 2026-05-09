package com.lifepilot.memory.eval.loader;

import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 统一评估用例。Loader 加载后把不同 benchmark 的异构格式转换为本 record，屏蔽下游差异。
 *
 * @param caseId   用例 ID（Loader 内部稳定，用于 session 命名和日志追溯）
 * @param benchmark 所属基准名，如 {@code "locomo"} / {@code "longmemeval"}
 * @param sessions 按时间顺序的对话历史
 * @param questions 针对对话历史的评估题
 * @param metadata 可选元数据（例：LoCoMo 的 persona 信息）
 * @author zsg
 * @since 2026-05-09
 */
public record BenchmarkCase(
        String caseId,
        String benchmark,
        List<BenchmarkSession> sessions,
        List<BenchmarkQuestion> questions,
        @Nullable Map<String, String> metadata
) {
    public BenchmarkCase {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId 不能为空");
        }
        if (benchmark == null || benchmark.isBlank()) {
            throw new IllegalArgumentException("benchmark 不能为空");
        }
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
        questions = questions == null ? List.of() : List.copyOf(questions);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
