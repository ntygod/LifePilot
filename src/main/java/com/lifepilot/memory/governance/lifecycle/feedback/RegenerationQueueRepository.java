package com.lifepilot.memory.governance.lifecycle.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 派生实体重算队列（{@code derivation_regeneration_queue}）访问层。
 *
 * <p>队列条目由 {@code DerivedEntityListener} 在源失效（SUPERSEDED / CANCELLED /
 * EXPIRED）触发派生实体进入 {@code REGENERATION_NEEDED} 时写入，后续由
 * {@code DerivationRegenerator} Cron（Task 29）按 {@code status='PENDING'} 顺序消费，
 * 状态迁移：{@code PENDING → PROCESSING → DONE}（失败走 {@code FAILED}）。</p>
 *
 * <p>本仓库只负责入队与基础计数，状态推进由消费者更新。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class RegenerationQueueRepository {

    private static final Logger log = LoggerFactory.getLogger(RegenerationQueueRepository.class);

    private final JdbcTemplate jdbc;

    public RegenerationQueueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 为指定派生实体入一条 {@code PENDING} 重算记录。
     *
     * <p>不去重 —— 同一派生实体若有多个源相继失效，允许多条 PENDING 并存；消费端按
     * {@code (derived_entity_id)} 归并即可。</p>
     *
     * @param derivedEntityId       待重算的派生实体 ID
     * @param triggerSourceEntityId 触发本次重算的源实体 ID
     * @param when                  入队时间
     */
    public void enqueue(String derivedEntityId, String triggerSourceEntityId, Instant when) {
        jdbc.update(
                """
                INSERT INTO derivation_regeneration_queue
                    (id, derived_entity_id, trigger_source_entity_id, status, created_at)
                VALUES (?, ?, ?, 'PENDING', ?)
                """,
                UUID.randomUUID().toString(),
                derivedEntityId,
                triggerSourceEntityId,
                when.toString());
        log.debug("派生实体重算入队: derived={}, trigger={}, when={}",
                derivedEntityId, triggerSourceEntityId, when);
    }

    /**
     * 测试 / 监控辅助：统计指定派生实体仍处于 {@code PENDING} 状态的条目数。
     *
     * @param derivedEntityId 派生实体 ID
     * @return PENDING 行数
     */
    public int countPendingByDerived(String derivedEntityId) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM derivation_regeneration_queue
                 WHERE derived_entity_id = ? AND status = 'PENDING'
                """,
                Integer.class, derivedEntityId);
        return n == null ? 0 : n;
    }

    /**
     * Phase 3 Task 29 DerivationRegenerator 使用 —— 按 created_at 升序拉取最多 {@code limit}
     * 条 {@code PENDING} 队列项。
     *
     * <p>消费端对拿到的每条结果调 {@link #markDone} 或 {@link #markFailed} 推进状态。</p>
     *
     * @param limit 批次大小上限
     * @return 队列项列表（按 created_at 升序）
     */
    public List<QueueItem> findPending(int limit) {
        return jdbc.query(
                """
                SELECT id, derived_entity_id, trigger_source_entity_id
                FROM derivation_regeneration_queue
                WHERE status = 'PENDING'
                ORDER BY created_at ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new QueueItem(
                        rs.getString("id"),
                        rs.getString("derived_entity_id"),
                        rs.getString("trigger_source_entity_id")),
                limit);
    }

    /**
     * 将队列项标记为 {@code DONE} 并回填 {@code processed_at}。
     *
     * @param id 队列项 id
     */
    public void markDone(String id) {
        jdbc.update(
                """
                UPDATE derivation_regeneration_queue
                SET status = 'DONE', processed_at = ?
                WHERE id = ?
                """,
                Instant.now().toString(), id);
    }

    /**
     * 将队列项标记为 {@code FAILED}。
     *
     * <p>当前队列表不持久化失败原因，仅通过 {@code logger.warn} 打印到日志。</p>
     *
     * @param id     队列项 id
     * @param reason 失败原因（仅用于日志）
     */
    public void markFailed(String id, String reason) {
        jdbc.update(
                """
                UPDATE derivation_regeneration_queue
                SET status = 'FAILED', processed_at = ?
                WHERE id = ?
                """,
                Instant.now().toString(), id);
        log.warn("派生实体重算失败: queueId={}, reason={}", id, reason);
    }

    /**
     * 队列项轻量视图 —— 承载消费端需要的最小字段。
     *
     * @param id                    队列项 id
     * @param derivedEntityId       待重算的派生实体 ID
     * @param triggerSourceEntityId 触发本次重算的源实体 ID
     */
    public record QueueItem(String id, String derivedEntityId, String triggerSourceEntityId) {}
}
