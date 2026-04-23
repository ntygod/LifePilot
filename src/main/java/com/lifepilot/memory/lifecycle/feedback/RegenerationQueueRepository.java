package com.lifepilot.memory.lifecycle.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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
}
