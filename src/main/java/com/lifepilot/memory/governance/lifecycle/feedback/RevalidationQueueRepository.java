package com.lifepilot.memory.governance.lifecycle.feedback;

import com.lifepilot.memory.governance.lifecycle.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 陈旧引用再验证队列数据访问仓库 —— 封装 {@code memory_revalidation_queue} 表。
 *
 * <p>队列条目由 {@code ReValidationListener} 在源失效时写入，状态迁移：
 * {@code PENDING → PROMPTED → RESOLVED} —— {@code PROMPTED} 表示已被检索层捎带给 LLM
 * 提示复核，{@code RESOLVED} 由 LLM 反馈或人工处理关闭。本仓库负责入队、基础计数与
 * 人工关闭动作，复杂复核策略由更上层的检索 / 复核流程驱动。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class RevalidationQueueRepository {

    private static final Logger log = LoggerFactory.getLogger(RevalidationQueueRepository.class);

    private final JdbcTemplate jdbc;

    public RevalidationQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc 不能为空");
    }

    /**
     * 为指定实体入一条 PENDING 再验证记录。
     *
     * <p>不做去重 —— 同一 (entityId, sourceType, sourceId) 如被多次失效（比如文档先归档
     * 后内容变化）允许多条 PENDING 并存，检索层可按 created_at 取最新一条。</p>
     *
     * @param entityId 实体 ID
     * @param type     失效源类型
     * @param sourceId 源对象 ID
     * @param when     入队时间
     */
    public void enqueue(String entityId, SourceType type, String sourceId, Instant when) {
        String cleanEntityId = requireCleanText(entityId, "entityId");
        Objects.requireNonNull(type, "type 不能为空");
        String cleanSourceId = requireCleanText(sourceId, "sourceId");
        Objects.requireNonNull(when, "when 不能为空");
        int affected = jdbc.update(
                """
                INSERT INTO memory_revalidation_queue
                    (id, entity_id, source_type, source_id, created_at, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                """,
                UUID.randomUUID().toString(),
                cleanEntityId,
                type.name(),
                cleanSourceId,
                when.toString());
        if (affected != 1) {
            throw new IllegalStateException("再验证队列入队影响行数异常: affected=" + affected);
        }
        log.debug("再验证队列入队: entity={}, source={}:{}, when={}",
                cleanEntityId, type, cleanSourceId, when);
    }

    /**
     * 测试 / 监控辅助：统计指定 source 下仍处于 PENDING 状态的条目数。
     *
     * @param type     源类型
     * @param sourceId 源 ID
     * @return PENDING 行数
     */
    public int countPendingBySource(SourceType type, String sourceId) {
        Objects.requireNonNull(type, "type 不能为空");
        String cleanSourceId = requireCleanText(sourceId, "sourceId");
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM memory_revalidation_queue
                 WHERE source_type = ? AND source_id = ? AND status = 'PENDING'
                """,
                Integer.class, type.name(), cleanSourceId);
        if (n == null) {
            throw new IllegalStateException("再验证队列计数结果不能为空");
        }
        return n;
    }

    /**
     * 人工确认实体仍有效后，关闭实体下所有未完成的再验证任务。
     *
     * <p>只关闭 {@code PENDING}/{@code PROMPTED} 队列项，保留 provenance 的 {@code STALE}
     * 审计事实，避免抹掉来源曾经失效的历史。</p>
     *
     * @param entityId 实体 ID
     * @return 被关闭的队列行数
     */
    public int markResolvedByEntityId(String entityId) {
        String cleanEntityId = requireCleanText(entityId, "entityId");
        int affected = jdbc.update(
                """
                UPDATE memory_revalidation_queue
                   SET status = 'RESOLVED'
                 WHERE entity_id = ?
                   AND status IN ('PENDING', 'PROMPTED')
                """,
                cleanEntityId);
        log.debug("再验证队列人工关闭: entity={}, affected={}", cleanEntityId, affected);
        return affected;
    }

    private static String requireCleanText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (!value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " 不能包含首尾空白: " + value);
        }
        return value;
    }
}
