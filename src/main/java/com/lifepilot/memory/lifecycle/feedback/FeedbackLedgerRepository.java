package com.lifepilot.memory.lifecycle.feedback;

import com.lifepilot.memory.lifecycle.WeightSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * 反馈累计账本数据访问仓库 —— 封装 {@code memory_feedback_ledger} 表。
 *
 * <p>每次 {@link com.lifepilot.memory.lifecycle.events.EntityWeightChanged} 事件到达都
 * 追加一行，承担两项职责：
 * <ol>
 *   <li>供审计：完整保留反馈 delta / cumulativeScore 时序变化，便于回溯实体"为什么被降权"</li>
 *   <li>供阈值判定：{@link NegativeFeedbackListener} 基于 {@link #countNegative} 判断是否
 *       满足 {@code memory.feedback.negative-threshold-count} 触发 SUPERSEDED</li>
 * </ol>
 *
 * <p>表 CHECK 约束限定 {@code source} 取值 {@code USER_FEEDBACK / EFFECTIVENESS /
 * QUALITY_REJECT} —— 与 {@link WeightSource} 枚举一一对应，插入外来源会被 SQLite 拒绝。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class FeedbackLedgerRepository {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLedgerRepository.class);

    private final JdbcTemplate jdbc;

    public FeedbackLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条反馈账本记录。
     *
     * @param entityId        实体 ID
     * @param delta           本次权重变化量（正赞负踩）
     * @param cumulativeScore 调整后的 importance 累计分
     * @param source          反馈来源
     * @param when            入账时间
     */
    public void append(String entityId,
                       double delta,
                       double cumulativeScore,
                       WeightSource source,
                       Instant when) {
        jdbc.update(
                """
                INSERT INTO memory_feedback_ledger
                    (id, entity_id, source, delta, cumulative_score, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                entityId,
                source.name(),
                delta,
                cumulativeScore,
                when.toString());
        log.debug("反馈账本: 入账 entity={}, delta={}, cumulative={}, source={}",
                entityId, delta, cumulativeScore, source);
    }

    /**
     * 统计指定实体全部负向反馈（{@code delta < 0}）累计次数。
     *
     * @param entityId 实体 ID
     * @return 负向记录行数
     */
    public int countNegative(String entityId) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM memory_feedback_ledger
                 WHERE entity_id = ? AND delta < 0
                """,
                Integer.class, entityId);
        return n == null ? 0 : n;
    }
}
