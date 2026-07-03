package com.lifepilot.memory.consumption.hot;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

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
        Objects.requireNonNull(digestId, "热摘要 ID 不能为空");
        Objects.requireNonNull(viewKey, "热摘要视图 key 不能为空");
        Objects.requireNonNull(builtAt, "热摘要构建时间不能为空");
        Objects.requireNonNull(sourceRevision, "热摘要来源版本不能为空");
        sections = List.copyOf(Objects.requireNonNull(sections, "热摘要分区不能为空"));
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
            Objects.requireNonNull(kind, "热摘要分区类型不能为空");
            Objects.requireNonNull(content, "热摘要分区内容不能为空");
            sourceEntityIds = List.copyOf(Objects.requireNonNull(sourceEntityIds, "热摘要来源实体不能为空"));
            if (tokenBudget <= 0) {
                throw new IllegalArgumentException("热摘要分区 tokenBudget 必须大于 0: " + tokenBudget);
            }
        }
    }
}
