package com.lifepilot.memory.store.procedural;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * L4 偏好规则数据访问仓库 — 专供 {@code L4SyncListener} 使用的失活路径。
 *
 * <p>本仓库用 "{@code deactivated_reason IS NULL}" 作为"该规则仍然有效"的判定条件，
 * 向 {@code deactivated_reason} 写入理由即表示规则失活。</p>
 *
 * <p>{@link ProceduralMemory} 的 CRUD 路径保留原样 —— 本仓库仅负责生命周期联动更新，
 * 与巩固管线写入路径互不干扰。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@Repository
public class PreferenceRuleRepository {

    private static final Logger log = LoggerFactory.getLogger(PreferenceRuleRepository.class);

    private final JdbcTemplate jdbcTemplate;

    public PreferenceRuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 将指定源实体关联的仍处于活跃状态（{@code deactivated_reason IS NULL}）的偏好规则
     * 批量失活，写入理由。
     *
     * <p>{@code source_entity_id} 为空的记录不受影响（WHERE 条件已过滤）。</p>
     *
     * @param sourceEntityId 来源实体 ID
     * @param reason         失活理由（一般为 {@code LifecycleState.name()} 或 event.reason()）
     * @return 实际被失活的记录数（0 表示无对应活跃规则，无需告警）
     */
    public int deactivateBySourceEntity(String sourceEntityId, String reason) {
        int affected = jdbcTemplate.update(
                """
                UPDATE preference_rules
                   SET deactivated_reason = ?
                 WHERE source_entity_id = ?
                   AND deactivated_reason IS NULL
                """,
                reason, sourceEntityId);
        if (affected > 0) {
            log.debug("L4 偏好规则失活: sourceEntityId={}, reason={}, affected={}",
                    sourceEntityId, reason, affected);
        }
        return affected;
    }

    /**
     * 测试辅助：列出指定源实体对应的偏好规则 ID。
     *
     * @param sourceEntityId 来源实体 ID
     * @return 规则 ID 列表（可能为空）
     */
    public List<String> findRuleIdsBySourceEntity(String sourceEntityId) {
        return jdbcTemplate.queryForList(
                "SELECT rule_id FROM preference_rules WHERE source_entity_id = ?",
                String.class, sourceEntityId);
    }
}
