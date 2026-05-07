package com.lifepilot.memory.hot;

import java.time.Instant;
import java.util.List;

/**
 * L3.5 热记忆摘要快照。
 *
 * <p>热摘要是消费投影，不是事实主库。内容必须能追溯到 L3 source entities，便于解释与治理。</p>
 *
 * @param digestId        摘要版本 ID（可用于日志/审计）
 * @param viewKey         读取视图 key（主账户/项目/域等）
 * @param builtAt         构建时间
 * @param sourceRevision  来源集合版本（hash），用于判定是否需要重建
 * @param sections        各分区摘要
 *
 * @author zsg
 * @since 2026-05-05
 */
public record HotMemoryDigest(
        String digestId,
        String viewKey,
        Instant builtAt,
        String sourceRevision,
        List<HotMemorySection> sections
) {
    public HotMemoryDigest {
        sections = sections != null ? List.copyOf(sections) : List.of();
    }

    /**
     * 热摘要分区。
     *
     * @param kind            分区类型
     * @param content         可直接注入 Prompt 的文本（已脱敏）
     * @param sourceEntityIds 来源实体 ID 列表
     * @param tokenBudget     本分区预算
     *
     * @author zsg
     * @since 2026-05-05
     */
    public record HotMemorySection(
            HotMemorySectionKind kind,
            String content,
            List<String> sourceEntityIds,
            int tokenBudget
    ) {
        public HotMemorySection {
            content = content != null ? content : "";
            sourceEntityIds = sourceEntityIds != null ? List.copyOf(sourceEntityIds) : List.of();
        }
    }
}

